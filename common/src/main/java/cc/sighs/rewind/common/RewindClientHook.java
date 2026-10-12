package cc.sighs.rewind.common;

import javax.annotation.Nullable;

/**
 * common 侧留的一个中立小接口：让**共享命令层**能安全地调用「只有客户端才有」的那几件事。
 *
 * <p>为什么需要它：{@code /rewind ui} 与 {@code /rewind status} 的客户端状态展示原先直接引用
 * {@code cc.sighs.rewind.client.RewindTreeScreen} / {@code CheckpointController}，而命令现在要在
 * **服务端入口**注册（专用服务器上也要有 {@code /rewind}），那些客户端类在专用服务器上不存在。
 * 做成一个不引用任何 Minecraft 类型的接口、由客户端 setup 时装进来，命令层就能两端共用：
 * 装了就转发，没装（专用服务器）就如实报告「这里没有客户端」。
 *
 * <p>它没有任何状态，链接进来的实现是客户端自己的静态方法（客户端类由调用点的接口引用触发加载，
 * 专用服务器上永远不会加载）。
 */
public final class RewindClientHook {
    /** 客户端能提供的那几件事。 */
    public interface Ui {
        /** 打开时间树（F9 那条路）。 */
        void openTree();

        /** 当前客户端状态机的阶段名（{@code IDLE} / {@code WORKING}…）。 */
        String phase();

        /** 最近一次操作的结果名（{@code NONE} / {@code SUCCESS} / {@code FAILED}）。 */
        String outcome();

        /** 最近一条日志里的话（没有就是空串）。 */
        String message();
    }

    private static volatile Ui ui;

    /** 由客户端在 setup 时调用一次。 */
    public static void install(Ui hook) {
        ui = hook;
    }

    /** 客户端钩子；专用服务器 / 还没 setup 时为 null。 */
    @Nullable
    public static Ui get() {
        return ui;
    }

    private RewindClientHook() {
    }
}
