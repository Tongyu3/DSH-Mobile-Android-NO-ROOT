/**
 * 宿主侧（Node 进程）—— 这里什么都不做。
 *
 * 这个插件的全部逻辑都在浏览器侧（lib/client.js）：
 * 它只是往 DSH 的右侧栏注册一个新的标签页「手机文件」。
 * 列目录与"加进会话"都走 App 注入到页面里的那两个桥：
 *   window.DshAndroid.listFiles(path)     列目录（App 侧限定在共享存储内）
 *   window.__dshPhoneFilesInsert(paths)   把选中的路径写进输入框
 *
 * 为什么不用 Host 侧开 HTTP 接口：那要走 DSH 的 Remote/typert 一整套机器，
 * 而这两个能力本来就只在这台手机上才有意义 —— 桥更直接，也更好排查。
 *
 * 导出的 apply 是 DSH 加载插件时要求的形状（空实现也要有）。
 */
export const name = "phone-files";

export function apply() { }
