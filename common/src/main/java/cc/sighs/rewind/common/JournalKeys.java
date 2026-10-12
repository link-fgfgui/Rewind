package cc.sighs.rewind.common;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 写账本 / 数据覆盖层的**键函数**，以及 1.20.1 的「从存档文件夹反推维度」。
 *
 * <p><b>为什么单独一个类，而不是留在 Mixin 里</b>：这些函数既要被 Mixin（记账）用，也要被回滚引擎
 * （{@code InPlaceRollback} 读覆盖层）用。放在 {@code cc.sighs.mixin} 包里的话，后者就是
 * 「非 Mixin 类直接引用 Mixin 包里的类」——Mixin 在运行时会以
 * {@code IllegalClassLoadError: ... is in a defined mixin package ... and cannot be referenced directly}
 * 拒绝加载（专用服务器上实测会炸在原地回滚的灌装阶段）。键的格式必须两边完全一致，所以搬到一个
 * 普通类里、两边都调它。**键字符串是磁盘外的约定，改动会让已有账本失配，别改格式。**
 */
public final class JournalKeys {
    /** 文件夹路径 → 维度 id 的解析缓存；空串 = 未识别（缓存下来，同一路径不用反复 stat）。 */
    private static final Map<Path, String> FOLDER_DIMENSIONS = new ConcurrentHashMap<>();

    private JournalKeys() {
    }

    /** 存档数据文件（{@code <维度>/data/*.dat}、{@code level.dat}）的账本键。 */
    public static String savedataKey(File file) {
        return "savedata|" + file.toPath().toAbsolutePath().normalize();
    }

    /** 存档数据文件（26.1 侧直接拿的 {@code Path}）的账本键。 */
    public static String savedataKey(Path file) {
        return "savedata|" + file.toAbsolutePath().normalize();
    }

    /** 数据目录 + id（不带 {@code .dat}）的账本键。 */
    public static String savedataKey(File dataFolder, String id) {
        return "savedata|" + new File(dataFolder, id + ".dat").toPath().toAbsolutePath().normalize();
    }

    /** 玩家数据（{@code playerdata/<uuid>.dat}）的账本键。 */
    public static String playerKey(String uuid) {
        return "player|" + uuid;
    }

    /**
     * 1.20.1 的存档文件夹布局反推维度；**未识别返回 null**（写侧跳过记账，
     * 回滚侧走 {@code .mca} 头部比对回退——猜错比不认更危险）。
     *
     * <ul>
     *   <li>{@code <存档>/region|entities|poi}：父目录就是存档根（放着 {@code level.dat}）→ 主世界；</li>
     *   <li>{@code <存档>/DIM-1/...} → 下界，{@code <存档>/DIM1/...} → 末地；</li>
     *   <li>{@code <存档>/dimensions/<ns>/<path...>/...} → {@code <ns>:<path>}（原版目录规范，
     *       数据包维度与多数模组走这个布局；path 段可带 {@code /}）；</li>
     *   <li>其余布局（模组完全自定义）→ {@code null}。</li>
     * </ul>
     */
    public static String dimensionOfFolder(Path folder) {
        if (folder == null) {
            return null;
        }
        String cached = FOLDER_DIMENSIONS.get(folder);
        if (cached != null) {
            return cached.isEmpty() ? null : cached;
        }
        String dimension = resolveDimension(folder);
        FOLDER_DIMENSIONS.put(folder, dimension == null ? "" : dimension);
        return dimension;
    }

    private static String resolveDimension(Path regionFolder) {
        // region / entities / poi 三类存储目录的父目录就是维度根
        Path dimensionRoot = regionFolder.getParent();
        if (dimensionRoot == null) {
            return null;
        }
        // <存档>/dimensions/<ns>/<path...>/：从后往前找最近的 dimensions 段，其后至少两段
        List<String> segments = new ArrayList<>();
        for (Path segment : dimensionRoot) {
            segments.add(segment.toString());
        }
        for (int i = segments.size() - 3; i >= 0; i--) {
            if (!"dimensions".equals(segments.get(i))) {
                continue;
            }
            StringBuilder path = new StringBuilder();
            for (int j = i + 2; j < segments.size(); j++) {
                if (path.length() > 0) {
                    path.append('/');
                }
                path.append(segments.get(j));
            }
            return segments.get(i + 1) + ":" + path;
        }
        String name = dimensionRoot.getFileName() == null ? null : dimensionRoot.getFileName().toString();
        if ("DIM-1".equals(name)) {
            return "minecraft:the_nether";
        }
        if ("DIM1".equals(name)) {
            return "minecraft:the_end";
        }
        // 主世界：三类存储目录直接挂在存档根下，存档根放着 level.dat
        if (Files.isRegularFile(dimensionRoot.resolve("level.dat"))) {
            return "minecraft:overworld";
        }
        return null;
    }
}
