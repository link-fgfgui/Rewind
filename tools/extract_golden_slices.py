#!/usr/bin/env python3
"""
从 mc-backups.zip 提取轻量级 Golden Slices，供日常自动化单元测试使用。

Level-A（随仓库提交，FastCI 纯离线毫秒级）：
  common/src/test/resources/goldenslices/
    t1_base_chunk_14_18.nbt / t1_target_chunk_14_18.nbt   35s 微变区块（未压缩 NBT）
    t1_base_disk_manager.dat / t1_target_disk_manager.dat AE2 大单体（.dat 原样，gzip）
    manifest.json                                            sha256/尺寸清单

Level-B（本地全量回放，不提交，REWIND_TEST_SLICES_DIR 或 common/build/goldenslices-t4/）：
  T4 13:00 -> 14:00 逐 region 比对出的全部差异 chunk 对 + 差异 .dat 对 + manifest.json。

用法：
  python3 tools/extract_golden_slices.py                      # 只提 Level-A（默认）
  python3 tools/extract_golden_slices.py --with-t4             # 另提 Level-B（需本地 mc-backups.zip）
  REWIND_TEST_BACKUPS_ZIP=/path/mc-backups.zip python3 tools/extract_golden_slices.py --with-t4
"""
import gzip
import hashlib
import io
import json
import os
import struct
import sys
import tarfile
import zipfile
import zlib

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DEFAULT_ZIP = os.path.join(os.path.dirname(REPO_ROOT), "rewind-others", "mc-backups.zip")
ZIP_PATH = os.environ.get("REWIND_TEST_BACKUPS_ZIP", DEFAULT_ZIP)
LEVEL_A_DIR = os.path.join(REPO_ROOT, "common", "src", "test", "resources", "goldenslices")
LEVEL_B_DIR = os.environ.get(
    "REWIND_TEST_SLICES_DIR", os.path.join(REPO_ROOT, "common", "build", "goldenslices-t4"))

T1_A = "mc-backups/manual/manual-20260820-224142.tar.gz"
T1_B = "mc-backups/manual/manual-20260820-224217.tar.gz"
T4_A = "mc-backups/mc-backup-20261010-130006.tar.gz"
T4_B = "mc-backups/mc-backup-20261010-140005.tar.gz"


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def extract_chunk_nbt(mca_bytes, chunk_x, chunk_z):
    """Anvil location 表定位 + 5 字节头截断，返回未压缩 NBT（版本 1/2/3；外溢/LZ4 返回 None）。"""
    if len(mca_bytes) < 8192:
        return None
    idx = (chunk_z % 32) * 32 + (chunk_x % 32)
    loc = struct.unpack(">I", mca_bytes[idx * 4:(idx + 1) * 4])[0]
    if loc == 0:
        return None
    offset = (loc >> 8) * 4096
    length = struct.unpack(">I", mca_bytes[offset:offset + 4])[0]
    if length <= 1 or offset + 4 + length > len(mca_bytes):
        return None
    compression = mca_bytes[offset + 4]
    payload = mca_bytes[offset + 5:offset + 4 + length]
    if compression == 2:
        return zlib.decompress(payload)
    if compression == 1:
        return gzip.decompress(payload)
    if compression == 3:
        return payload
    return None


def open_tar(zf, name):
    return tarfile.open(fileobj=io.BytesIO(zf.read(name)), mode="r:gz")


def find_padded_sector(mca_bytes, want_versions=(1, 2), min_pad=64):
    """找一个带末尾补零的已分配扇区：返回 (x, z, version, i1, sector_bytes, raw_nbt)。"""
    for idx in range(1024):
        loc = struct.unpack(">I", mca_bytes[idx * 4:(idx + 1) * 4])[0]
        if loc == 0:
            continue
        sector_num = loc >> 8
        sector_count = loc & 0xFF
        if sector_num < 2 or sector_count == 0:
            continue
        offset = sector_num * 4096
        if offset + 5 > len(mca_bytes):
            continue
        i1 = struct.unpack(">I", mca_bytes[offset:offset + 4])[0]
        version = mca_bytes[offset + 4]
        if version not in want_versions or i1 <= 1:
            continue
        alloc = sector_count * 4096
        if alloc - (4 + i1) < min_pad:
            continue
        payload = mca_bytes[offset + 5:offset + 4 + i1]
        try:
            if version == 2:
                raw = zlib.decompress(payload)
            elif version == 1:
                raw = gzip.decompress(payload)
            else:
                continue
        except Exception:
            continue
        x = (idx % 32)
        z = (idx // 32)
        return x, z, version, i1, mca_bytes[offset:offset + alloc], raw
    return None


def extract_level_a(zf):
    os.makedirs(LEVEL_A_DIR, exist_ok=True)
    manifest = {}
    print("[Level-A] T1 35s 微变切片 (chunk 14,18) ...")
    ta = open_tar(zf, T1_A)
    tb = open_tar(zf, T1_B)
    mca1 = ta.extractfile("world/region/r.0.0.mca").read()
    mca2 = tb.extractfile("world/region/r.0.0.mca").read()
    c1 = extract_chunk_nbt(mca1, 14, 18)
    c2 = extract_chunk_nbt(mca2, 14, 18)
    assert c1 and c2, "chunk (14,18) 缺席，zip 结构与 NATIVE_TEST.md 不符"
    assert len(c1) == len(c2), "T1 长度不一致：%d vs %d" % (len(c1), len(c2))
    diff = sum(1 for x, y in zip(c1, c2) if x != y)
    print("  chunk(14,18): %d B, %d B 相异" % (len(c1), diff))
    for name, data in (("t1_base_chunk_14_18.nbt", c1), ("t1_target_chunk_14_18.nbt", c2)):
        with open(os.path.join(LEVEL_A_DIR, name), "wb") as f:
            f.write(data)
        manifest[name] = {"bytes": len(data), "sha256": sha256(data)}
    print("[Level-A] AE2 disk_manager.dat 切片 ...")
    d1 = ta.extractfile("world/data/disk_manager.dat").read()
    d2 = tb.extractfile("world/data/disk_manager.dat").read()
    r1, r2 = gzip.decompress(d1), gzip.decompress(d2)
    print("  disk_manager: gzip %d/%d B -> raw %d/%d B" % (len(d1), len(d2), len(r1), len(r2)))
    for name, data in (("t1_base_disk_manager.dat", d1), ("t1_target_disk_manager.dat", d2)):
        with open(os.path.join(LEVEL_A_DIR, name), "wb") as f:
            f.write(data)
        manifest[name] = {"bytes": len(data), "sha256": sha256(data),
                          "raw_bytes": len(gzip.decompress(data)),
                          "raw_sha256": sha256(gzip.decompress(data))}
    print("[Level-A] Anvil 边界扇区切片（含 padToFullSector 补零） ...")
    # 注：该生产服 region 全用 DEFLATE（117 个 region 文件零 GZIP 扇区），故只有 deflate 切片；
    # GZIP 流解码由 disk_manager.dat 切片覆盖（见上），版本分派其余分支由单测合成数据覆盖。
    found = {}
    for mca in (mca1, mca2):
        hit = find_padded_sector(mca)
        if hit is None:
            continue
        x, z, version, i1, sector, raw = hit
        tag = "gzip" if version == 1 else "deflate"
        if tag in found:
            continue
        found[tag] = (x, z, version, i1, sector, raw)
        print("  %s: chunk(%d,%d) i1=%d payload=%d alloc=%d raw=%d" % (
            tag, x, z, i1, i1 - 1, len(sector), len(raw)))
    assert found, "r.0.0.mca 里没找到带补零的 GZIP/DEFLATE 扇区"
    for tag, (x, z, version, i1, sector, raw) in sorted(found.items()):
        for name, data in (("boundary_%s_sector.bin" % tag, sector),
                           ("boundary_%s_expected.nbt" % tag, raw)):
            with open(os.path.join(LEVEL_A_DIR, name), "wb") as f:
                f.write(data)
            manifest[name] = {"bytes": len(data), "sha256": sha256(data)}
        manifest["boundary_%s_sector.bin" % tag].update(
            {"chunk": [x, z], "version": version, "i1": i1})
    with open(os.path.join(LEVEL_A_DIR, "manifest.json"), "w") as f:
        json.dump(manifest, f, indent=2, sort_keys=True)
    print("  ->", LEVEL_A_DIR)


def region_names(tf):
    return sorted(m.name for m in tf.getmembers()
                  if m.isfile() and "/region/r." in m.name
                  and m.name.endswith(".mca") and "/datapacks/" not in m.name)


def extract_level_b(zf):
    os.makedirs(LEVEL_B_DIR, exist_ok=True)
    print("[Level-B] T4 13:00 -> 14:00 全差异 chunk 对 ...")
    ta = open_tar(zf, T4_A)
    tb = open_tar(zf, T4_B)
    names = sorted(set(region_names(ta)) & set(region_names(tb)))
    print("  region 文件：%d 个共有" % len(names))
    pairs = []
    for name in names:
        ma = ta.extractfile(name).read()
        mb = tb.extractfile(name).read()
        if len(ma) < 8192 or len(mb) < 8192:
            continue
        parts = name.split("r.")[-1].split(".")
        rx, rz = int(parts[0]), int(parts[1])
        for i in range(1024):
            la = struct.unpack(">I", ma[i * 4:(i + 1) * 4])[0]
            lb = struct.unpack(">I", mb[i * 4:(i + 1) * 4])[0]
            if la == lb:
                continue
            x = (i % 32) + rx * 32
            z = (i // 32) + rz * 32
            ra = extract_chunk_nbt(ma, x, z)
            rb = extract_chunk_nbt(mb, x, z)
            if ra is None and rb is None:
                continue
            if ra is not None and rb is not None and ra == rb:
                continue  # 仅扇区搬家，内容一致
            pairs.append((name, x, z, ra, rb))
    pairs.sort(key=lambda p: (p[0], p[1], p[2]))
    print("  差异 chunk 对：%d" % len(pairs))
    manifest = {"pairs": []}
    total = 0
    for n, (name, x, z, ra, rb) in enumerate(pairs):
        base_name = "t4_pair_%03d_base.nbt" % n
        tgt_name = "t4_pair_%03d_target.nbt" % n
        # 缺席侧（新建/删除）记空文件，manifest 标注
        ba = ra if ra is not None else b""
        ta_ = rb if rb is not None else b""
        with open(os.path.join(LEVEL_B_DIR, base_name), "wb") as f:
            f.write(ba)
        with open(os.path.join(LEVEL_B_DIR, tgt_name), "wb") as f:
            f.write(ta_)
        total += len(ba) + len(ta_)
        manifest["pairs"].append({
            "region": name, "x": x, "z": z,
            "base": {"file": base_name, "bytes": len(ba), "sha256": sha256(ba),
                     "absent": ra is None},
            "target": {"file": tgt_name, "bytes": len(ta_), "sha256": sha256(ta_),
                       "absent": rb is None},
        })
    print("[Level-B] 差异 .dat 对（≤16 个，单体 ≤4 MiB raw） ...")
    dat_names = sorted(
        m.name for m in ta.getmembers()
        if m.isfile() and m.name.endswith(".dat") and "/datapacks/" not in m.name
        and m.name in set(mm.name for mm in tb.getmembers()))
    dat_pairs = []
    for name in dat_names:
        da = ta.extractfile(name).read()
        try:
            db = tb.extractfile(name).read()
        except KeyError:
            continue
        if da == db:
            continue
        try:
            ra, rb = gzip.decompress(da), gzip.decompress(db)
        except OSError:
            continue
        if len(ra) > 4 * 1024 * 1024 or len(rb) > 4 * 1024 * 1024:
            continue
        dat_pairs.append((name, da, db))
        if len(dat_pairs) >= 16:
            break
    manifest["dat_pairs"] = []
    for n, (name, da, db) in enumerate(dat_pairs):
        safe = name.replace("/", "_")
        base_name = "t4_dat_%02d_base.dat" % n
        tgt_name = "t4_dat_%02d_target.dat" % n
        with open(os.path.join(LEVEL_B_DIR, base_name), "wb") as f:
            f.write(da)
        with open(os.path.join(LEVEL_B_DIR, tgt_name), "wb") as f:
            f.write(db)
        total += len(da) + len(db)
        manifest["dat_pairs"].append({
            "path": name, "base": base_name, "target": tgt_name,
            "base_bytes": len(da), "target_bytes": len(db),
        })
        print("  %s: %d -> %d B (gzip)" % (name, len(da), len(db)))
    manifest["total_bytes"] = total
    with open(os.path.join(LEVEL_B_DIR, "manifest.json"), "w") as f:
        json.dump(manifest, f, indent=2, sort_keys=True)
    print("  pairs=%d dat_pairs=%d total=%.1f MiB -> %s"
          % (len(pairs), len(dat_pairs), total / 1048576.0, LEVEL_B_DIR))


def main():
    with_t4 = "--with-t4" in sys.argv
    if not os.path.isfile(ZIP_PATH):
        print("找不到 %s（可用 REWIND_TEST_BACKUPS_ZIP 覆盖），跳过提取" % ZIP_PATH)
        return 1
    print("zip:", ZIP_PATH)
    with zipfile.ZipFile(ZIP_PATH, "r") as zf:
        extract_level_a(zf)
        if with_t4:
            extract_level_b(zf)
        else:
            print("（加 --with-t4 另提 Level-B 全量回放对）")
    print("完毕")


if __name__ == "__main__":
    main()
