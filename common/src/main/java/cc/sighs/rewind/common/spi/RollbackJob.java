package cc.sighs.rewind.common.spi;

/**
 * 一次**跨 tick** 的原地回滚作业（TODO4 阶段 2 的「方案 A：回滚切片化」）。
 *
 * <p>多人在线（专用服务器 / 局域网）时不能把回滚整段跑在一个 tick 里：服务端线程一卡，
 * keep-alive 应答就排不上队，客户端会在 30 秒的超时后断开。所以版本侧的引擎把自己的
 * 分阶段结构改成这个有状态机——每 tick 由 common 的调度器拿一个时间预算来推一片，
 * 到预算就把控制权还给 tick 循环（连接、心跳、包处理照常跑）。
 *
 * <p><b>上下文</b>：调度器在开始前已经 {@code RewindState.beginDiscard()} +
 * {@code beginMultiplayerRollback()}（世界落盘被跳过、世界刻被冻结、操作包被丢弃），
 * 结束时无论成败都会 {@code endDiscard()} + {@code endMultiplayerRollback()}。
 * 作业自己不碰这两个标记。
 *
 * <p><b>线程</b>：{@link #step}、{@link #outcome}、{@link #failure}、{@link #abort}
 * 都只在服务端线程上被调用。
 *
 * <p><b>超时</b>：调度器有一个整体预算（见 {@code MultiplayerRollback}），超了就调
 * {@link #abort()}，然后按失败收尾。实现要保证 abort 幂等、且能接受「跑到一半」的状态：
 * 把覆盖层撤掉、把名字清掉，剩下的交给世界自身的生命周期。
 */
public interface RollbackJob {
    /** 一句话描述（进日志）：这次在回滚哪个槽位 / 什么阶段。 */
    String describe();

    /**
     * 跑一片有界的工作。
     *
     * <p>实现要自己看时间：{@code budgetNanos} 是这一片还能占用的墙钟纳秒数（从「现在」算起），
     * 到了就赶紧返回。**至少要有进展**——调度器在一个 tick 里会反复调用它直到超预算或结束，
     * 另外整段回滚还有一个跨 tick 的总预算，空转不推进会撞上总超时。
     *
     * @return {@code true} = 整段回滚已经结束（成败看 {@link #outcome()} / {@link #failure()}）
     */
    boolean step(long budgetNanos);

    /** 结束时的回滚结果；还没结束或彻底失败（带异常）时为 null。 */
    RollbackOutcome outcome();

    /** 结束时带着的异常；成功结束或还没结束时为 null。 */
    Throwable failure();

    /** 整段回滚是否已经结束（{@link #step} 返回 true 之后置位）。 */
    boolean finished();

    /**
     * 超时中止：清掉版本侧的中途状态（覆盖层、卸载名单、临时标记）。
     *
     * <p>幂等，可能在作业的任何阶段被调用。中止后这次回滚按失败收尾，**不做**客户端重载。
     */
    void abort();
}
