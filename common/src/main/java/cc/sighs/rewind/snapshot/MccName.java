package cc.sighs.rewind.snapshot;

/**
 * {@code c.<x>.<z>.mcc} 外溢载荷文件名的解析与生成（一对互逆操作）。
 *
 * <p>名字只有这一种合法形态（由原版 {@code RegionFile} / zpack 外溢写入生成）；
 * {@link #parse} 解不出的路径调用方按损坏/不适用处理。
 *
 * <p>纯 JDK、Java 8 兼容。
 */
public final class MccName {
    public final int x;
    public final int z;

    private MccName(int x, int z) {
        this.x = x;
        this.z = z;
    }

    /** 生成 {@code c.<x>.<z>.mcc}（不含目录部分）。 */
    public static String format(int x, int z) {
        return "c." + x + "." + z + ".mcc";
    }

    /** 解析 {@code c.<x>.<z>.mcc}；形态不符返回 null。 */
    public static MccName parse(String fileName) {
        if (fileName == null || !fileName.startsWith("c.") || !fileName.endsWith(".mcc")) {
            return null;
        }
        String coords = fileName.substring(2, fileName.length() - 4);
        int dot = coords.indexOf('.');
        if (dot < 0) {
            return null;
        }
        try {
            return new MccName(Integer.parseInt(coords.substring(0, dot)),
                    Integer.parseInt(coords.substring(dot + 1)));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
