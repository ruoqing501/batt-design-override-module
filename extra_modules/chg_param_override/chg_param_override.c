#include <linux/module.h>
#include <linux/kernel.h>
#include <linux/init.h>
#include <linux/device.h>
#include <linux/power_supply.h>
#include <linux/kprobes.h>
#include <linux/uaccess.h>
#include <linux/mutex.h>
#include <linux/fs.h>
#include <linux/file.h>
#include <linux/slab.h>
#include <linux/timer.h>
#include <linux/notifier.h>
#include <linux/workqueue.h>
#include <linux/version.h>
#include <linux/kmod.h>
#include <linux/notifier.h>
#include <linux/workqueue.h>

/* 允许通过内核态写 pd_verifed（使用 VFS 内部 API） */
#define DISABLE_PD_VERIFED 1

/* PMIC Glink 消息定义（用于拦截 pmic_glink_write） */
#define MSG_OWNER_BC			32778
#define MSG_TYPE_REQ_RESP		1
#define BC_USB_STATUS_SET		0x33
#define USB_INPUT_CURR_LIMIT		5  /* 从 qti_battery_charger.c 的枚举定义 */

/* PMIC Glink 消息头结构 */
struct pmic_glink_hdr {
	unsigned int owner;
	unsigned int type;
	unsigned int opcode;
	unsigned int len;
};

/* Battery Charger 请求消息结构 */
struct battery_charger_req_msg {
	struct pmic_glink_hdr hdr;
	unsigned int battery_id;
	unsigned int property_id;
	unsigned int value;
};

/*
 * chg_param_override: 通过三层拦截机制在 power_supply 层和 PMIC 层覆盖/注入可写参数，
 * 结合用户态（LSPosed Hook 应用）经 procfs 接口写入期望的充电参数，实现：
 * - 目标电压 voltage_max (uV) - 控制电池充电电压
 * - 恒流/终止电流 constant_charge_current / charge_termination_current (uA) [若驱动支持]
 * - USB 输入电流限制 input_current_limit (uA) - 限制 USB 输入电流
 * - USB 输入电压限制 input_voltage_limit (uV) - 限制 USB 输入电压（用于 PPS 功率控制）
 * - 充电功率控制：通过同时设置 voltage_max × constant_charge_current 控制电池充电功率
 *   或通过 input_voltage_limit × input_current_limit 控制 USB 输入功率（PPS）
 * - 充电速率/限速：通过限制 input_current_limit 或调整 constant_charge_current 实现
 * - PD 协议切换：控制 pd_verifed 在 PPS (1) 和 MIPPS (0) 间切换
 *
 * 三层拦截机制（按优先级从高到低）：
 * 1) pmic_glink_write kprobe: 在消息发送到PMIC硬件之前拦截，直接修改原始消息结构
 *    完全绕过所有驱动检查，最有效（当前支持ICL）
 * 2) power_supply_set_property kprobe: 在驱动处理之前拦截，修改传入的参数值
 *    用于处理IVL和其他参数，绕过部分驱动检查
 * 3) power_supply_set_property kretprobe: 在驱动处理之后拦截，覆盖返回值为成功
 *    用于欺骗上层调用者，即使驱动返回错误也显示成功
 *
 * 为兼容性，本实现提供一个简洁的 proc 接口：/proc/chg_param_override
 * 用户可写入简写行格式： key=value 换行分隔
 * 例如：
 *   voltage_max=4460000
 *   ccc=6000000
 *   icl=1500000
 *   ivl=5000000
 */
 

static char target_batt[32] = "battery";
module_param_string(target_batt, target_batt, sizeof(target_batt), 0644);
MODULE_PARM_DESC(target_batt, "power_supply name for battery (default: battery)");

static char target_usb[16] = "usb";
module_param_string(target_usb, target_usb, sizeof(target_usb), 0644);
MODULE_PARM_DESC(target_usb, "power_supply name for usb (default: usb)");

static bool verbose = true;
module_param(verbose, bool, 0644);

static bool auto_reapply = true;
module_param(auto_reapply, bool, 0644);
MODULE_PARM_DESC(auto_reapply, "Auto reapply pd_verifed setting after cable replug");

// PD Verified 路径
#if !DISABLE_PD_VERIFED
static char pd_verifed_path[128] = "/sys/class/qcom-battery/pd_verifed";
module_param_string(pd_verifed_path, pd_verifed_path, sizeof(pd_verifed_path), 0644);
MODULE_PARM_DESC(pd_verifed_path, "Path to pd_verifed sysfs node");
#endif

struct chg_targets {
    /* 原有充电参数 - 单位 uV / uA 按内核约定 */
    int voltage_max_uv;                /* 电池目标电压 */
    int constant_charge_current_ua;    /* 电池恒流（近似充电电流上限） */
    int term_current_ua;               /* 终止电流（若驱动支持 set_property） */
    int usb_input_current_limit_ua;    /* USB 输入电流限制 */
    int usb_input_voltage_limit_uv;    /* USB 输入电压限制（用于 PPS 功率控制） */
    
    /* 新增：电池充电控制 */
    int charge_control_limit_percent;  /* 充电限制百分比 (0-100) */
    
    /* 新增：PD 协议控制（可选，默认禁用以兼容 GKI） */
    int pd_verifed;                    /* PD Verified: 0=MIPPS, 1=PPS */
    bool pd_verifed_enabled;           /* 是否启用 pd_verifed 控制 */
    int last_pd_verifed;               /* 上次读取的 pd_verifed 值 */
};

static struct chg_targets g_targets;
static DEFINE_MUTEX(g_lock);

/* 事件驱动自动重写：power_supply 通知 + 延迟工作合并写入 */
static struct notifier_block psy_nb;
static struct delayed_work reapply_work;

/* 前向声明，供工作队列回调调用 */
static int apply_targets_locked(void);

static void reapply_work_fn(struct work_struct *work)
{
    mutex_lock(&g_lock);
    (void)apply_targets_locked();
    mutex_unlock(&g_lock);
}

static int psy_event_handler(struct notifier_block *nb, unsigned long event, void *data)
{
    struct power_supply *psy = data;
    const char *name;

    if (event != PSY_EVENT_PROP_CHANGED || !psy || !psy->desc)
        return NOTIFY_DONE;

    name = psy->desc->name;
    if (!name)
        return NOTIFY_DONE;

    /* 仅对我们关心的电源触发，合并频繁事件避免抖动 */
    if (!strcmp(name, target_batt) || !strcmp(name, target_usb)) {
        schedule_delayed_work(&reapply_work, msecs_to_jiffies(200));
        return NOTIFY_OK;
    }
    return NOTIFY_DONE;
}

#if !DISABLE_PD_VERIFED
/* 通过用户态助手写 sysfs，避免依赖 VFS 内部符号 */
static int umh_write_sysfs_int(const char *path, int value)
{
    char cmd[256];
    char *argv[] = { "/system/bin/sh", "-c", cmd, NULL };
    /* 追加常见 PATH，确保 echo/tee 可用 */
    char *envp[] = {
        "HOME=/",
        "TERM=linux",
        "PATH=/system/bin:/system/xbin:/system/vendor/bin:/vendor/bin:/odm/bin",
        NULL,
    };
    int rc;
    scnprintf(cmd, sizeof(cmd), "echo %d > %s", value, path);
    rc = call_usermodehelper(argv[0], argv, envp, UMH_WAIT_PROC);
    return rc;
}
#endif

/* PD Verified 控制函数 */
#if !DISABLE_PD_VERIFED
static int set_pd_verifed(int value)
{
    int ret;
    if (value != 0 && value != 1)
        return -EINVAL;
    ret = umh_write_sysfs_int(pd_verifed_path, value);
    if (ret == 0)
        g_targets.last_pd_verifed = value;
    return ret;
}
#endif

#if !DISABLE_PD_VERIFED
static int get_pd_verifed(void)
{
    /* 当前不读取，返回不支持，避免依赖 VFS/捕获输出 */
    return -EOPNOTSUPP;
}
#endif

static struct power_supply *find_psy_by_name(const char *name)
{
    return power_supply_get_by_name(name);
}

static int write_psy_int(struct power_supply *psy, enum power_supply_property psp, int val)
{
    int ret;
    union power_supply_propval prop = {0};
    struct power_supply_desc *desc;
    if (!psy)
        return -ENODEV;
    desc = (struct power_supply_desc *)psy->desc;
    if (!desc || !desc->set_property)
        return -EOPNOTSUPP;
    prop.intval = val;
    ret = power_supply_set_property(psy, psp, &prop);
    return ret;
}

static int apply_targets_locked(void)
{
    int ret = 0, rc;
    struct power_supply *batt = NULL, *usb = NULL;

    /* 应用 PD Verified 设置（若启用且未禁用该特性） */
#if !DISABLE_PD_VERIFED
    if (g_targets.pd_verifed_enabled) {
        rc = set_pd_verifed(g_targets.pd_verifed);
        if (rc && verbose)
            pr_info("chg_param_override: set pd_verifed failed %d\n", rc);
    }
#endif

    batt = find_psy_by_name(target_batt);
    usb  = find_psy_by_name(target_usb);

    if (batt) {
        if (g_targets.voltage_max_uv > 0) {
            rc = write_psy_int(batt, POWER_SUPPLY_PROP_VOLTAGE_MAX, g_targets.voltage_max_uv);
            if (rc && verbose)
                pr_info("chg_param_override: set VMAX failed %d\n", rc);
        }
        if (g_targets.constant_charge_current_ua > 0) {
            rc = write_psy_int(batt, POWER_SUPPLY_PROP_CONSTANT_CHARGE_CURRENT,
                               g_targets.constant_charge_current_ua);
            if (rc && verbose)
                pr_info("chg_param_override: set CCC failed %d\n", rc);
        }
        if (g_targets.term_current_ua > 0) {
            rc = write_psy_int(batt, POWER_SUPPLY_PROP_CHARGE_TERM_CURRENT,
                               g_targets.term_current_ua);
            if (rc && verbose)
                pr_info("chg_param_override: set TERM failed %d\n", rc);
        }
        if (g_targets.charge_control_limit_percent > 0) {
            rc = write_psy_int(batt, POWER_SUPPLY_PROP_CHARGE_CONTROL_LIMIT,
                               g_targets.charge_control_limit_percent);
            if (rc && verbose)
                pr_info("chg_param_override: set charge_control_limit failed %d\n", rc);
        }
    }

    if (usb) {
        if (g_targets.usb_input_current_limit_ua > 0) {
            rc = write_psy_int(usb, POWER_SUPPLY_PROP_INPUT_CURRENT_LIMIT,
                               g_targets.usb_input_current_limit_ua);
            if (rc && verbose) {
                pr_info("chg_param_override: set ICL failed %d\n", rc);
                pr_info("chg_param_override: note: kretprobe should override return value if interception was active\n");
                pr_info("chg_param_override: note: pmic_glink_write interception may not trigger if driver fails before calling it\n");
            }
        }
        /* 支持 PPS 充电：同时控制输入电压和电流以实现精确功率控制 */
        if (g_targets.usb_input_voltage_limit_uv > 0) {
            rc = write_psy_int(usb, POWER_SUPPLY_PROP_INPUT_VOLTAGE_LIMIT,
                               g_targets.usb_input_voltage_limit_uv);
            if (rc && verbose) {
                pr_info("chg_param_override: set IVL failed %d\n", rc);
                pr_info("chg_param_override: note: kretprobe should override return value if interception was active\n");
            }
        }
    }
    if (batt)
        power_supply_put(batt);
    if (usb)
        power_supply_put(usb);
    return ret;
}

/* ========== procfs 接口 ========== */
#include <linux/proc_fs.h>

static struct proc_dir_entry *proc_entry;

static ssize_t proc_read(struct file *file, char __user *buf, size_t count, loff_t *ppos)
{
    char kbuf[512];
    int len;
    if (*ppos)
        return 0;
    mutex_lock(&g_lock);
    len = scnprintf(kbuf, sizeof(kbuf),
        "batt=%s usb=%s\n"
        "voltage_max=%d\n"
        "ccc=%d\n"
        "term=%d\n"
        "icl=%d\n"
        "ivl=%d\n"
        "auto_reapply=%s\n",
        target_batt, target_usb,
        g_targets.voltage_max_uv,
        g_targets.constant_charge_current_ua,
        g_targets.term_current_ua,
        g_targets.usb_input_current_limit_ua,
        g_targets.usb_input_voltage_limit_uv,
        auto_reapply ? "yes" : "no");
    mutex_unlock(&g_lock);
    if (len > count)
        len = count;
    if (copy_to_user(buf, kbuf, len))
        return -EFAULT;
    *ppos += len;
    return len;
}

static int parse_kv(const char *key, const char *val)
{
    int v;
    if (!strcmp(key, "voltage_max") && kstrtoint(val, 10, &v) == 0) {
        g_targets.voltage_max_uv = v;
    } else if ((!strcmp(key, "constant_charge_current") || !strcmp(key, "ccc")) && kstrtoint(val, 10, &v) == 0) {
        g_targets.constant_charge_current_ua = v;
    } else if ((!strcmp(key, "term") || !strcmp(key, "charge_term_current")) && kstrtoint(val, 10, &v) == 0) {
        g_targets.term_current_ua = v;
    } else if ((!strcmp(key, "icl") || !strcmp(key, "input_current_limit")) && kstrtoint(val, 10, &v) == 0) {
        g_targets.usb_input_current_limit_ua = v;
    } else if ((!strcmp(key, "ivl") || !strcmp(key, "input_voltage_limit")) && kstrtoint(val, 10, &v) == 0) {
        g_targets.usb_input_voltage_limit_uv = v;
    } else if ((!strcmp(key, "charge_limit") || !strcmp(key, "charge_control_limit")) && kstrtoint(val, 10, &v) == 0) {
        if (v >= 0 && v <= 100) {
            g_targets.charge_control_limit_percent = v;
        } else {
            return -EINVAL;
        }
    } else if (!strcmp(key, "pd_verifed") && kstrtoint(val, 10, &v) == 0) {
        if (v == 0 || v == 1) {
            g_targets.pd_verifed = v;
            g_targets.pd_verifed_enabled = true;
        } else {
            return -EINVAL;
        }
    } else if (!strcmp(key, "pd_verifed_disable")) {
        g_targets.pd_verifed_enabled = false;
    } else if (!strcmp(key, "batt")) {
        strscpy(target_batt, val, sizeof(target_batt));
    } else if (!strcmp(key, "usb")) {
        strscpy(target_usb, val, sizeof(target_usb));
    } else {
        return -EINVAL;
    }
    return 0;
}

static ssize_t proc_write(struct file *file, const char __user *buf, size_t count, loff_t *ppos)
{
    char *kbuf, *line, *kv, *val;
    int rc = 0;
    if (count == 0 || count > PAGE_SIZE)
        return -EINVAL;
    kbuf = kzalloc(count + 1, GFP_KERNEL);
    if (!kbuf)
        return -ENOMEM;
    if (copy_from_user(kbuf, buf, count)) {
        kfree(kbuf);
        return -EFAULT;
    }
    mutex_lock(&g_lock);
    line = strim(kbuf);
    while (line && *line) {
        kv = strsep(&line, "\n");
        if (!kv)
            break;
        kv = strim(kv);
        if (!*kv)
            continue;
        val = strchr(kv, '=');
        if (!val) {
            rc = -EINVAL;
            break;
        }
        *val = '\0';
        val++;
        rc = parse_kv(kv, val);
        if (rc)
            break;
    }
    if (!rc)
        rc = apply_targets_locked();
    mutex_unlock(&g_lock);
    kfree(kbuf);
    if (rc)
        return rc;
    return count;
}

#if (LINUX_VERSION_CODE >= KERNEL_VERSION(5,6,0))
static const struct proc_ops proc_fops = {
    .proc_read  = proc_read,
    .proc_write = proc_write,
};
#else
static ssize_t legacy_read(struct file *file, char __user *buf, size_t count, loff_t *ppos)
{ return proc_read(file, buf, count, ppos); }
static ssize_t legacy_write(struct file *file, const char __user *buf, size_t count, loff_t *ppos)
{ return proc_write(file, buf, count, ppos); }
static const struct file_operations proc_fops = {
    .owner = THIS_MODULE,
    .read  = legacy_read,
    .write = legacy_write,
};
#endif

/* 监控和自动重新应用功能 */
static struct timer_list monitor_timer;

static void monitor_timer_callback(struct timer_list *t)
{
    /* 在禁用 PD 控制的构建中避免未使用变量 */
#if !DISABLE_PD_VERIFED
    int current_pd_verifed;
#endif
    
    if (!g_targets.pd_verifed_enabled || !auto_reapply) {
        mod_timer(&monitor_timer, jiffies + msecs_to_jiffies(5000));
        return;
    }
#if !DISABLE_PD_VERIFED
    current_pd_verifed = get_pd_verifed();
    if (current_pd_verifed >= 0) {
        mutex_lock(&g_lock);
        if (current_pd_verifed != g_targets.pd_verifed && 
            g_targets.last_pd_verifed == g_targets.pd_verifed) {
            // pd_verifed 被重置了（通常是插拔充电线），重新应用设置
            if (verbose)
                pr_info("chg_param_override: pd_verifed reset detected (%d->%d), reapplying settings\n",
                        g_targets.pd_verifed, current_pd_verifed);
            apply_targets_locked();
        }
        mutex_unlock(&g_lock);
    }
#endif
    
    // 每5秒检查一次
    mod_timer(&monitor_timer, jiffies + msecs_to_jiffies(5000));
}

/* ========== 可选：在 show/get_property 路径覆盖显示值，确保用户可读到生效值 ========== */
struct ps_show_args {
    struct device *dev;
    struct device_attribute *da;
    char *buf;
};
static struct kretprobe ps_show_kretprobe;

/* 针对 qti_battery_charger 的 pd_verifed_show：强制读取为 1 */
struct class_show_args {
    void *cls;
    void *attr;
    char *buf;
};
static struct kretprobe pd_show_kretprobe;

/* ========== 拦截 set_property 以绕过驱动限制 ========== */
static struct kprobe ps_set_kprobe;
static struct kretprobe ps_set_kretprobe;

/* ========== 拦截 pmic_glink_write 以直接修改发送给电源IC的消息 ========== */
static struct kprobe pmic_glink_write_kprobe;

/* 拦截 pmic_glink_write 的入口，修改发送给电源IC的消息 */
static int pmic_glink_write_entry_handler(struct kprobe *kp, struct pt_regs *regs)
{
#if defined(CONFIG_ARM64)
    void *client;
    void *data;
    size_t len;
    struct battery_charger_req_msg *req_msg;
    
    /* 从寄存器获取函数参数 (pmic_glink_write(client, data, len)) */
    client = (void *)regs->regs[0];
    data = (void *)regs->regs[1];
    len = (size_t)regs->regs[2];
    
    if (!data || len < sizeof(struct battery_charger_req_msg))
        return 0;
    
    req_msg = (struct battery_charger_req_msg *)data;
    
    /* 检查是否是 Battery Charger 的消息 */
    if (req_msg->hdr.owner != MSG_OWNER_BC || 
        req_msg->hdr.type != MSG_TYPE_REQ_RESP ||
        req_msg->hdr.opcode != BC_USB_STATUS_SET)
        return 0;
    
    /* 
     * CRITICAL: This runs in kprobe context (atomic/interrupt context).
     * We CANNOT sleep, so we CANNOT take mutex_lock(&g_lock).
     * We read g_targets without lock. Since they are simple integers,
     * tearing is rare and acceptable.
     */
    
    /* 检查是否是 USB 输入电流限制设置 */
    if (req_msg->property_id == USB_INPUT_CURR_LIMIT && 
        g_targets.usb_input_current_limit_ua > 0) {
        unsigned int orig_val = req_msg->value;
        req_msg->value = g_targets.usb_input_current_limit_ua;
        if (verbose)
            pr_info("chg_param_override: intercepted pmic_glink_write ICL msg, property_id=%u, overriding %u -> %u\n", 
                    req_msg->property_id, orig_val, g_targets.usb_input_current_limit_ua);
    }
    /* 注意：INPUT_VOLTAGE_LIMIT 可能没有对应的 property_id，需要根据实际情况添加 */
    
#endif
    return 0;
}

/* 用于保存拦截信息的结构 */
struct ps_set_intercept_info {
    bool should_override_result;  // 是否应该覆盖返回值
    enum power_supply_property psp;  // 属性类型
    int target_value;  // 目标值
};

/* 拦截 power_supply_set_property 的入口，在驱动层之前修改参数 */
static int set_entry_handler(struct kprobe *kp, struct pt_regs *regs)
{
#if defined(CONFIG_ARM64)
    struct power_supply *psy;
    enum power_supply_property psp;
    union power_supply_propval *val;
    const char *name = NULL;
    
    /* 从寄存器获取函数参数 */
    psy = (struct power_supply *)regs->regs[0];
    psp = (enum power_supply_property)regs->regs[1];
    val = (union power_supply_propval *)regs->regs[2];
    
    if (!psy || !psy->desc || !val)
        return 0;
    
    name = psy->desc->name;
    if (!name)
        return 0;
    
    /* 
     * CRITICAL: This runs in kprobe context (atomic/interrupt context).
     * We CANNOT sleep, so we CANNOT take mutex_lock(&g_lock).
     * We read g_targets without lock. Since they are simple integers,
     * tearing is rare and acceptable.
     */
    
    /* 拦截 USB 输入电流限制设置 */
    if (!strcmp(name, target_usb)) {
        if (psp == POWER_SUPPLY_PROP_INPUT_CURRENT_LIMIT && 
            g_targets.usb_input_current_limit_ua > 0) {
            /* 保存原始值用于日志 */
            int orig_val = val->intval;
            /* 覆盖传入的值为我们想要的值 */
            val->intval = g_targets.usb_input_current_limit_ua;
            if (verbose)
                pr_info("chg_param_override: intercepted ICL set to %d, overriding to %d\n", 
                        orig_val, g_targets.usb_input_current_limit_ua);
        }
        /* 拦截 USB 输入电压限制设置 */
        else if (psp == POWER_SUPPLY_PROP_INPUT_VOLTAGE_LIMIT && 
                 g_targets.usb_input_voltage_limit_uv > 0) {
            /* 保存原始值用于日志 */
            int orig_val = val->intval;
            /* 覆盖传入的值为我们想要的值 */
            val->intval = g_targets.usb_input_voltage_limit_uv;
            if (verbose)
                pr_info("chg_param_override: intercepted IVL set to %d, overriding to %d\n", 
                        orig_val, g_targets.usb_input_voltage_limit_uv);
        }
    }
    /* 也可以拦截电池相关参数（如果需要） */
    else if (!strcmp(name, target_batt)) {
        if (psp == POWER_SUPPLY_PROP_VOLTAGE_MAX && 
            g_targets.voltage_max_uv > 0) {
            val->intval = g_targets.voltage_max_uv;
            if (verbose)
                pr_info("chg_param_override: intercepted VMAX set, overriding to %d\n", 
                        g_targets.voltage_max_uv);
        } else if (psp == POWER_SUPPLY_PROP_CONSTANT_CHARGE_CURRENT && 
                   g_targets.constant_charge_current_ua > 0) {
            val->intval = g_targets.constant_charge_current_ua;
            if (verbose)
                pr_info("chg_param_override: intercepted CCC set, overriding to %d\n", 
                        g_targets.constant_charge_current_ua);
        } else if (psp == POWER_SUPPLY_PROP_CHARGE_TERM_CURRENT && 
                   g_targets.term_current_ua > 0) {
            val->intval = g_targets.term_current_ua;
            if (verbose)
                pr_info("chg_param_override: intercepted TERM set, overriding to %d\n", 
                        g_targets.term_current_ua);
        }
    }
#endif
    return 0;
}

/* 拦截 power_supply_set_property 的返回，如果驱动返回错误但我们已经拦截了参数，则返回成功 */
static int set_ret_handler(struct kretprobe_instance *ri, struct pt_regs *regs)
{
#if defined(CONFIG_ARM64)
    struct ps_set_intercept_info *info = (struct ps_set_intercept_info *)ri->data;
    long ret_val;
    unsigned long ret_val_unsigned;
    
    if (!info) {
        if (verbose)
            pr_warn("chg_param_override: kretprobe handler: info is NULL\n");
        return 0;
    }
    
    /* 获取原始返回值（ARM64 返回值在 regs[0] 中） */
    ret_val_unsigned = (unsigned long)regs->regs[0];
    /* 将返回值视为 32 位有符号整数，然后符号扩展到 64 位 */
    /* 这样可以正确处理像 4294967274 (-22) 这样的值 */
    ret_val = (long)(int)ret_val_unsigned;
    
    if (verbose && (info->psp == POWER_SUPPLY_PROP_INPUT_CURRENT_LIMIT || 
                    info->psp == POWER_SUPPLY_PROP_INPUT_VOLTAGE_LIMIT)) {
        pr_info("chg_param_override: kretprobe handler: property=%d, ret_val=%ld (unsigned=%lu), should_override=%d\n",
                info->psp, ret_val, ret_val_unsigned, info->should_override_result);
    }
    
    /* 如果原始调用失败（返回非零值），但我们拦截了这个调用，则返回成功 */
    /* 注意：power_supply_set_property 返回 0 表示成功，非零表示错误 */
    /* 检查：ret_val != 0（任何非零返回值都表示错误） */
    if (info->should_override_result && ret_val != 0) {
        if (verbose) {
            pr_info("chg_param_override: set_property returned %ld (unsigned %lu), but overriding to 0 (success) for property %d\n", 
                    ret_val, ret_val_unsigned, info->psp);
        }
        /* 覆盖返回值为成功 */
        regs->regs[0] = 0;
    }
#endif
    return 0;
}

/* kretprobe 入口处理，保存拦截信息 */
static int set_ret_entry_handler(struct kretprobe_instance *ri, struct pt_regs *regs)
{
#if defined(CONFIG_ARM64)
    struct ps_set_intercept_info *info = (struct ps_set_intercept_info *)ri->data;
    struct power_supply *psy;
    enum power_supply_property psp;
    const char *name = NULL;
    
    if (!info)
        return 0;
    
    /* 从寄存器获取函数参数 */
    psy = (struct power_supply *)regs->regs[0];
    psp = (enum power_supply_property)regs->regs[1];
    
    if (!psy || !psy->desc)
        return 0;
    
    name = psy->desc->name;
    if (!name)
        return 0;
    
    /* 初始化拦截信息 */
    info->should_override_result = false;
    info->psp = psp;
    info->target_value = 0;
    
    /* 检查是否需要拦截返回值 */
    if (!strcmp(name, target_usb)) {
        if (psp == POWER_SUPPLY_PROP_INPUT_CURRENT_LIMIT && 
            g_targets.usb_input_current_limit_ua > 0) {
            info->should_override_result = true;
            info->target_value = g_targets.usb_input_current_limit_ua;
            if (verbose)
                pr_info("chg_param_override: kretprobe entry: will override ICL return value\n");
        } else if (psp == POWER_SUPPLY_PROP_INPUT_VOLTAGE_LIMIT && 
                   g_targets.usb_input_voltage_limit_uv > 0) {
            info->should_override_result = true;
            info->target_value = g_targets.usb_input_voltage_limit_uv;
            if (verbose)
                pr_info("chg_param_override: kretprobe entry: will override IVL return value\n");
        }
    }
#endif
    return 0;
}

static int show_entry_handler(struct kretprobe_instance *ri, struct pt_regs *regs)
{
#if defined(CONFIG_ARM64)
    struct ps_show_args *args = (struct ps_show_args *)ri->data;
    args->dev = (struct device *)regs->regs[0];
    args->da  = (struct device_attribute *)regs->regs[1];
    args->buf = (char *)regs->regs[2];
#endif
    return 0;
}

static int show_ret_handler(struct kretprobe_instance *ri, struct pt_regs *regs)
{
    struct ps_show_args *args = (struct ps_show_args *)ri->data;
    struct power_supply *psy;
    const char *name = NULL;
    const char *attr;
    ssize_t orig_ret, v;
    /* 
     * CRITICAL: This runs in kprobe context (atomic/interrupt context).
     * We CANNOT sleep, so we CANNOT take mutex_lock(&g_lock).
     * We read g_targets without lock. Since they are simple integers,
     * tearing is rare and acceptable for display purposes.
     */

    if (!args || !args->dev || !args->da || !args->buf)
        return 0;
    
    /* 检查原始返回值：只有在成功时（>= 0）才覆盖 */
#if defined(CONFIG_ARM64)
    orig_ret = (ssize_t)regs->regs[0];
#else
    /* 对于非 ARM64 架构，需要根据具体架构获取返回值 */
    orig_ret = 0; /* 假设成功，让后续逻辑处理 */
#endif
    
    /* 如果原始调用失败，不覆盖返回值 */
    if (orig_ret < 0)
        return 0;
    
    attr = args->da->attr.name;
    if (!attr)
        return 0;
    
    psy = dev_get_drvdata(args->dev);
    if (psy && psy->desc)
        name = psy->desc->name;
    
    if (!name)
        return 0;

    // mutex_lock(&g_lock); // REMOVED to prevent panic
    if (!strcmp(name, target_batt)) {
        if (!strcmp(attr, "voltage_max") && g_targets.voltage_max_uv > 0) {
            v = scnprintf(args->buf, PAGE_SIZE, "%d\n", g_targets.voltage_max_uv);
#if defined(CONFIG_ARM64)
            regs->regs[0] = (unsigned long)v;
#endif
        } else if (!strcmp(attr, "constant_charge_current") && g_targets.constant_charge_current_ua > 0) {
            v = scnprintf(args->buf, PAGE_SIZE, "%d\n", g_targets.constant_charge_current_ua);
#if defined(CONFIG_ARM64)
            regs->regs[0] = (unsigned long)v;
#endif
        } else if ((!strcmp(attr, "charge_termination_current") || !strcmp(attr, "charge_term_current"))
                   && g_targets.term_current_ua > 0) {
            v = scnprintf(args->buf, PAGE_SIZE, "%d\n", g_targets.term_current_ua);
#if defined(CONFIG_ARM64)
            regs->regs[0] = (unsigned long)v;
#endif
        }
    } else if (!strcmp(name, target_usb)) {
        if (!strcmp(attr, "input_current_limit") && g_targets.usb_input_current_limit_ua > 0) {
            v = scnprintf(args->buf, PAGE_SIZE, "%d\n", g_targets.usb_input_current_limit_ua);
#if defined(CONFIG_ARM64)
            regs->regs[0] = (unsigned long)v;
#endif
        } else if (!strcmp(attr, "input_voltage_limit") && g_targets.usb_input_voltage_limit_uv > 0) {
            v = scnprintf(args->buf, PAGE_SIZE, "%d\n", g_targets.usb_input_voltage_limit_uv);
#if defined(CONFIG_ARM64)
            regs->regs[0] = (unsigned long)v;
#endif
        }
    }
    // mutex_unlock(&g_lock); // REMOVED
    return 0;
}

/* pd_verifed_show 入口：保存 buf 指针 */
static int pd_show_entry(struct kretprobe_instance *ri, struct pt_regs *regs)
{
#if defined(CONFIG_ARM64)
    struct class_show_args *args = (struct class_show_args *)ri->data;
    args->cls  = (void *)regs->regs[0];
    args->attr = (void *)regs->regs[1];
    args->buf  = (char *)regs->regs[2];
#endif
    return 0;
}

/* pd_verifed_show 返回：强制写入 "1\n" 并覆盖返回长度 */
static int pd_show_ret(struct kretprobe_instance *ri, struct pt_regs *regs)
{
    struct class_show_args *args = (struct class_show_args *)ri->data;
    int v;
    if (!args || !args->buf)
        return 0;
    v = scnprintf(args->buf, PAGE_SIZE, "1\n");
#if defined(CONFIG_ARM64)
    regs->regs[0] = (unsigned long)v;
#endif
    return 0;
}

static int __init chg_override_init(void)
{
    int ret;
    
    /* 初始化目标结构 */
    memset(&g_targets, 0, sizeof(g_targets));
    
    proc_entry = proc_create("chg_param_override", 0666, NULL, &proc_fops);
    if (!proc_entry)
        return -ENOMEM;

    memset(&ps_show_kretprobe, 0, sizeof(ps_show_kretprobe));
    ps_show_kretprobe.handler = show_ret_handler;
    ps_show_kretprobe.entry_handler = show_entry_handler;
    ps_show_kretprobe.data_size = sizeof(struct ps_show_args);
    ps_show_kretprobe.maxactive = 32;
    ps_show_kretprobe.kp.symbol_name = "power_supply_show_property";
    ret = register_kretprobe(&ps_show_kretprobe);
    if (ret) {
        pr_err("chg_param_override: register show kretprobe failed %d\n", ret);
        remove_proc_entry("chg_param_override", NULL);
        return ret;
    }

    /* 注册 pd_verifed_show 覆盖（可选，仅高通平台有效） */
    memset(&pd_show_kretprobe, 0, sizeof(pd_show_kretprobe));
    pd_show_kretprobe.handler = pd_show_ret;
    pd_show_kretprobe.entry_handler = pd_show_entry;
    pd_show_kretprobe.data_size = sizeof(struct class_show_args);
    pd_show_kretprobe.maxactive = 16;
    pd_show_kretprobe.kp.symbol_name = "pd_verifed_show";
    ret = register_kretprobe(&pd_show_kretprobe);
    if (ret) {
        /* 非致命错误：如果找不到符号，仅禁用 PD 覆盖功能 */
        pr_warn("chg_param_override: pd_verifed_show symbol not found or hook failed (%d), PD override disabled\n", ret);
    } else {
        pr_info("chg_param_override: pd_verifed_show hooked\n");
    }

    /* 第一层和第二层拦截已禁用，仅使用第三层拦截（pmic_glink_write） */
#if 0
    /* 注册 power_supply_set_property 拦截（用于绕过驱动限制） */
    memset(&ps_set_kprobe, 0, sizeof(ps_set_kprobe));
    ps_set_kprobe.symbol_name = "power_supply_set_property";
    ps_set_kprobe.pre_handler = set_entry_handler;
    ret = register_kprobe(&ps_set_kprobe);
    if (ret) {
        /* 非致命错误：如果找不到符号，仅禁用 set_property 拦截 */
        pr_warn("chg_param_override: power_supply_set_property symbol not found or hook failed (%d), set_property interception disabled\n", ret);
    } else {
        pr_info("chg_param_override: power_supply_set_property hooked (will override ICL/IVL values)\n");
    }

    /* 注册 power_supply_set_property 的 kretprobe（用于覆盖返回值，即使驱动返回错误也返回成功） */
    memset(&ps_set_kretprobe, 0, sizeof(ps_set_kretprobe));
    ps_set_kretprobe.handler = set_ret_handler;
    ps_set_kretprobe.entry_handler = set_ret_entry_handler;
    ps_set_kretprobe.data_size = sizeof(struct ps_set_intercept_info);
    ps_set_kretprobe.maxactive = 32;
    ps_set_kretprobe.kp.symbol_name = "power_supply_set_property";
    ret = register_kretprobe(&ps_set_kretprobe);
    if (ret) {
        /* 非致命错误：如果注册失败，仅禁用返回值覆盖功能 */
        pr_warn("chg_param_override: power_supply_set_property kretprobe registration failed (%d), return value override disabled\n", ret);
    } else {
        pr_info("chg_param_override: power_supply_set_property kretprobe hooked (will override return values for ICL/IVL)\n");
    }
#endif

    /* 注册 pmic_glink_write 拦截（用于直接修改发送给电源IC的消息，绕过所有驱动检查） */
    memset(&pmic_glink_write_kprobe, 0, sizeof(pmic_glink_write_kprobe));
    pmic_glink_write_kprobe.symbol_name = "pmic_glink_write";
    pmic_glink_write_kprobe.pre_handler = pmic_glink_write_entry_handler;
    ret = register_kprobe(&pmic_glink_write_kprobe);
    if (ret) {
        /* 非致命错误：如果找不到符号，仅禁用 pmic_glink_write 拦截 */
        pr_warn("chg_param_override: pmic_glink_write symbol not found or hook failed (%d), pmic_glink_write interception disabled\n", ret);
    } else {
        pr_info("chg_param_override: pmic_glink_write hooked (will intercept and modify messages to power IC)\n");
    }

    /* 初始化监控定时器 */
    timer_setup(&monitor_timer, monitor_timer_callback, 0);
    mod_timer(&monitor_timer, jiffies + msecs_to_jiffies(5000));

    /* 注册 power_supply 通知与延迟工作 */
    INIT_DELAYED_WORK(&reapply_work, reapply_work_fn);
    psy_nb.notifier_call = psy_event_handler;
    ret = power_supply_reg_notifier(&psy_nb);
    if (ret) {
        del_timer_sync(&monitor_timer);
        unregister_kretprobe(&ps_show_kretprobe);
        remove_proc_entry("chg_param_override", NULL);
        pr_err("chg_param_override: reg notifier failed %d\n", ret);
        return ret;
    }

#if !DISABLE_PD_VERIFED
    pr_info("chg_param_override: loaded batt=%s usb=%s pd_path=%s\n", 
            target_batt, target_usb, pd_verifed_path);
#else
    pr_info("chg_param_override: loaded batt=%s usb=%s (pd_control=disabled)\n", 
            target_batt, target_usb);
#endif
    return 0;
}

static void __exit chg_override_exit(void)
{
    power_supply_unreg_notifier(&psy_nb);
    cancel_delayed_work_sync(&reapply_work);
    del_timer_sync(&monitor_timer);
    unregister_kretprobe(&pd_show_kretprobe);
    unregister_kretprobe(&ps_show_kretprobe);
#if 0
    /* 第一层和第二层拦截已禁用 */
    unregister_kretprobe(&ps_set_kretprobe);
    unregister_kprobe(&ps_set_kprobe);
#endif
    unregister_kprobe(&pmic_glink_write_kprobe);
    remove_proc_entry("chg_param_override", NULL);
    pr_info("chg_param_override: unloaded\n");
}

MODULE_LICENSE("GPL");
MODULE_AUTHOR("serein-213");
MODULE_DESCRIPTION("Override charger params with PD protocol control via power_supply and procfs");

module_init(chg_override_init);
module_exit(chg_override_exit);




