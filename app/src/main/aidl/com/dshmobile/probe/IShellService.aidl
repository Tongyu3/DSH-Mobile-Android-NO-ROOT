// Shizuku「用户服务」的接口。
//
// 为什么需要它：Shizuku 以 shell（uid 2000）或 root（uid 0）身份，在**独立进程**里
// 运行我们自己的 Java 代码。我们要的就是"以 shell 身份跑一条命令"，
// 于是这里只暴露一个 exec()。
//
// 注意 destroy() 的事务码：Shizuku 用它来销毁用户服务，**必须写成 16777114**。
// 写错的话服务不会被回收，会在后台留一个杀不掉的进程。
//
// 另外 AIDL 有一条硬规则：**要么每个方法都写 id，要么一个都别写**。
// 因为 destroy() 必须写 id，所以下面两个也各自显式编号。
package com.dshmobile.probe;

interface IShellService {

    /** 保留方法：Shizuku 调用它来回收用户服务。事务码由 Shizuku 规定，不可改。 */
    void destroy() = 16777114;

    /**
     * 以 shell/root 身份执行一条命令。
     *
     * @return 形如 "exit=0\n<stdout>\n<stderr>" 的文本（永不抛异常，失败也走返回值）
     */
    String exec(String command) = 1;

    /** 当前用户服务的 uid：0 = root，2000 = shell。用来在界面上说清楚拿到了什么权限。 */
    int uid() = 2;
}
