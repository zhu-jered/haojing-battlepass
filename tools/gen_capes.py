# -*- coding: utf-8 -*-
"""生成两张内置披风贴图（镐京开拓者 / 长夜守望），输出标准 64x32 PNG。

为什么需要这个脚本
------------------
`server/src/main/resources/haojing_battlepass/default_capes.json` 里定义了两件默认披风，
它们的 `textureFile` 指向 `capes/haojing_pioneer.png` 与 `capes/haojing_nightwatch.png`，
但仓库里从来没有这两张图（全盘 `**/*.png` 为 0）。缺图的后果是：玩家解锁并穿戴上以后，
`CapeManager` 读不到字节，服务端不下发贴图，客户端回退原版披风，等于这两件披风"看得见、穿不上"。

用法
----
    python tools/gen_capes.py                 # 写入 run/config/haojing_battlepass/capes/
    python tools/gen_capes.py --out <目录>     # 写入指定目录（正式服请指到真实 config 目录）
    python tools/gen_capes.py --list          # 只打印将要生成的清单，不写盘

只用标准库（zlib/struct/binascii），不需要 Pillow。脚本只写它自己的两个输出文件，
不碰 capes.json、不改动任何源码。重复执行安全：同样的输入总是得到同样的字节。

贴图布局
--------
玩家皮肤 64x32 纹理里的披风区域（与 Minecraft 自身皮肤格式一致）：
    顶面  (0,0)-(19,0)   x∈[0,19], y=0
    底面  (0,31)-(19,31) x∈[0,19], y=31
    正面  右起前 10 列   x∈[0,9],  y∈[1,16]
    背面  x∈[12,21],     y∈[1,16]
    两个侧面 x∈[22,31],  y∈[1,16]
其余像素保持全透明（alpha=0），不会被渲染。
"""

from __future__ import annotations

import argparse
import binascii
import os
import struct
import sys
import zlib

WIDTH = 64
HEIGHT = 32

# 披风区域：(x0, y0, x1, y1)，闭区间
SKIN_REGIONS = (
    (0, 0, 19, 0),
    (0, 31, 19, 31),
    (0, 1, 9, 16),
    (12, 1, 21, 16),
    (22, 1, 31, 16),
)

# 生成预设：文件名 -> (底布, 徽记/金饰, 包边)
CAPES = {
    "haojing_pioneer.png": {
        "base": (92, 30, 32),      # 深红褐：镐京旌旗
        "gold": (214, 180, 96),    # 旧金：徽记
        "rim": (58, 20, 22),       # 暗红：包边
    },
    "haojing_nightwatch.png": {
        "base": (32, 48, 84),      # 夜蓝：长夜
        "gold": (150, 180, 220),   # 银蓝：徽记
        "rim": (20, 32, 60),       # 深蓝：包边
    },
}


def in_cape_region(x: int, y: int) -> bool:
    """判断像素是否落在披风区域（区域外一律透明）。"""
    for x0, y0, x1, y1 in SKIN_REGIONS:
        if x0 <= x <= x1 and y0 <= y <= y1:
            return True
    return False


def emblem(x: int, y: int) -> bool:
    """正面/背面共用的徽记：中央菱形 + 中柱 + 上下两翼。

    参数 x 是该面内部的列号（0..9），y 是纹理行号（1..16）。
    """
    dx = abs(x - 4.5)
    if dx <= 2.5 - abs(y - 8.5) * 0.45:          # 中央菱形
        return True
    if dx <= 0.9 and 2 <= y <= 15:               # 中柱
        return True
    if 3 <= y <= 7 and 2.6 <= dx <= 4.1:         # 两侧上翼
        return True
    if 10 <= y <= 13 and 3.0 <= dx <= 4.1:       # 两侧下翼
        return True
    return False


def scale_tone(x: int, y: int) -> float:
    """龙鳞纹：按 (x + 3y) 周期性压暗，避免大片纯色。"""
    return 0.84 if (x + 3 * y) % 4 == 0 else 1.0


def dim(color, factor: float):
    """按亮度系数缩放颜色，并夹到 0..255。"""
    return (
        max(0, min(255, int(round(color[0] * factor)))),
        max(0, min(255, int(round(color[1] * factor)))),
        max(0, min(255, int(round(color[2] * factor)))),
    )


def build_pixels(palette) -> bytearray:
    """产出 RGBA 像素流（行优先，每像素 4 字节）。"""
    base = palette["base"]
    gold = palette["gold"]
    rim = palette["rim"]
    pixels = bytearray()

    for y in range(HEIGHT):
        for x in range(WIDTH):
            if not in_cape_region(x, y):
                pixels += b"\x00\x00\x00\x00"
                continue

            factor = scale_tone(x, y)
            is_top = y == 0
            is_bottom = y == HEIGHT - 1
            is_front = 0 <= x <= 9
            is_back = 12 <= x <= 21

            if is_top:
                factor *= 1.25           # 顶面受光
            elif is_bottom:
                factor *= 0.70           # 底面压暗
            elif x >= 22:
                factor *= 0.90 + 0.02 * (x - 22)   # 侧面沿横向渐亮

            # 徽记画在正面与背面
            if is_front and emblem(x, y):
                rgb = gold
            elif is_back and emblem(x - 12, y):
                rgb = gold
            elif x in (0, 19) and not is_top and not is_bottom:
                rgb = rim                # 左右包边
            else:
                rgb = dim(base, min(1.30, factor))

            pixels += bytes(rgb) + b"\xff"

    return pixels


def to_png(pixels: bytes) -> bytes:
    """把 RGBA 像素流封成 PNG（8bit truecolor+alpha，非隔行）。"""
    raw = bytearray()
    stride = WIDTH * 4
    for y in range(HEIGHT):
        raw.append(0)                                  # 每行 filter=0 (None)
        raw += pixels[y * stride:(y + 1) * stride]

    def chunk(tag: bytes, data: bytes) -> bytes:
        body = tag + data
        return struct.pack(">I", len(data)) + body + struct.pack(">I", binascii.crc32(body) & 0xFFFFFFFF)

    ihdr = struct.pack(">IIBBBBB", WIDTH, HEIGHT, 8, 6, 0, 0, 0)
    return (
        b"\x89PNG\r\n\x1a\n"
        + chunk(b"IHDR", ihdr)
        + chunk(b"IDAT", zlib.compress(bytes(raw), 9))
        + chunk(b"IEND", b"")
    )


def verify(png: bytes) -> None:
    """自检：PNG 签名、尺寸、尾部 IEND，以及 zlib 流能否解回原始行数据。"""
    assert png[:8] == b"\x89PNG\r\n\x1a\n", "PNG 签名错误"
    width, height = struct.unpack(">II", png[16:24])
    assert (width, height) == (WIDTH, HEIGHT), "尺寸错误：%dx%d" % (width, height)
    assert png[-12:] == b"\x00\x00\x00\x00IEND\xaeB`\x82", "IEND 块错误"

    # 反查 IDAT 并解压，确认像素行数正确（比仅看文件头更有说服力）
    offset = 8
    idat = bytearray()
    while offset < len(png):
        (length,) = struct.unpack(">I", png[offset:offset + 4])
        tag = png[offset + 4:offset + 8]
        data = png[offset + 8:offset + 8 + length]
        if tag == b"IDAT":
            idat += data
        offset += 12 + length
    raw = zlib.decompress(bytes(idat))
    assert len(raw) == HEIGHT * (1 + WIDTH * 4), "解压后的行数据长度不对"
    assert all(raw[y * (1 + WIDTH * 4)] == 0 for y in range(HEIGHT)), "行首 filter 字节不是 0"


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description="生成两张内置披风贴图（64x32 PNG）")
    parser.add_argument(
        "--out",
        default=os.path.join("run", "config", "haojing_battlepass", "capes"),
        help="输出目录，默认 run/config/haojing_battlepass/capes（开发环境 runClient/runServer 的 config 目录）",
    )
    parser.add_argument("--list", action="store_true", help="只打印清单，不写盘")
    args = parser.parse_args(argv)

    if args.list:
        for name in CAPES:
            print("%s  (%dx%d)" % (os.path.join(args.out, name), WIDTH, HEIGHT))
        return 0

    os.makedirs(args.out, exist_ok=True)
    for name, palette in CAPES.items():
        png = to_png(build_pixels(palette))
        verify(png)
        path = os.path.join(args.out, name)
        with open(path, "wb") as handle:
            handle.write(png)
        print("已生成 %s（%d 字节）" % (path, len(png)))

    print("")
    print("记得确认 capes.json 里的 textureFile 与本目录下的文件名一致：")
    for name in CAPES:
        print("  - " + name)
    print("服务端启动/热重载后会读取该目录；贴图变化会自动下发给在线客户端，玩家无需重穿。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
