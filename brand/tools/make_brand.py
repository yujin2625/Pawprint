"""brand/ 폴더의 모든 로고 에셋을 다시 생성한다.

사용법: python brand/tools/make_brand.py   (Pillow 필요)
"""
import os

from PIL import Image

from pixel_art import PAL, blank, cat16, cat24, put, tile, to_image

OUT = os.path.normpath(os.path.join(os.path.dirname(__file__), '..'))

# 5×7 도트 글자 (1 = 칠함)
GLYPHS = {
    'P': ['1110', '1001', '1001', '1110', '1000', '1000', '1000'],
    'A': ['0110', '1001', '1001', '1111', '1001', '1001', '1001'],
    'W': ['10001', '10001', '10001', '10101', '10101', '11011', '10001'],
    'R': ['1110', '1001', '1001', '1110', '1010', '1001', '1001'],
    'I': ['111', '010', '010', '010', '010', '010', '111'],
    'N': ['1001', '1101', '1101', '1011', '1011', '1001', '1001'],
    'T': ['11111'] + ['00100'] * 6,
}

# 글자색: 'PAW'는 호박색, 'PRINT'는 변형별 색
WORD_COLORS = {
    'dark': 'c',    # 어두운 배경용: 크림
    'light': 'R',   # 밝은 배경용: 청사진 남색
}


def wordmark(print_ch, scale=2):
    """'PAWPRINT' 글자를 그리드로 만든다. 글자 픽셀 하나 = scale×scale 칸."""
    word = 'PAWPRINT'
    width = sum(len(GLYPHS[ch][0]) for ch in word) + len(word) - 1
    g = blank(7 * scale)
    g = [['.'] * (width * scale) for _ in range(7 * scale)]
    x = 0
    for i, ch in enumerate(word):
        color = 'a' if i < 3 else print_ch
        rows = GLYPHS[ch]
        for gy, row in enumerate(rows):
            for gx, bit in enumerate(row):
                if bit == '1':
                    for dy in range(scale):
                        for dx in range(scale):
                            put(g, (x + gx) * scale + dx, gy * scale + dy, color)
        x += len(rows[0]) + 1
    return g


def horizontal(print_ch):
    """고양이(24px) + 간격 4px + 글자(높이 14px, 세로 가운데)."""
    cat = cat24()
    word = wordmark(print_ch)
    gap = 4
    w = 24 + gap + len(word[0])
    g = [['.'] * w for _ in range(24)]
    for y in range(24):
        for x in range(24):
            g[y][x] = cat[y][x]
    top = (24 - len(word)) // 2
    for y, row in enumerate(word):
        for x, ch in enumerate(row):
            if ch != '.':
                g[top + y][24 + gap + x] = ch
    return g


def to_svg(g, path):
    """같은 색의 가로 연속 픽셀을 하나의 rect로 묶은 SVG."""
    h, w = len(g), len(g[0])
    rects = []
    for y in range(h):
        x = 0
        while x < w:
            ch = g[y][x]
            if ch == '.':
                x += 1
                continue
            x2 = x
            while x2 + 1 < w and g[y][x2 + 1] == ch:
                x2 += 1
            r, gg, b = PAL[ch]
            rects.append(f'<rect x="{x}" y="{y}" width="{x2 - x + 1}" height="1" fill="#{r:02X}{gg:02X}{b:02X}"/>')
            x = x2 + 1
    svg = (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {w} {h}" width="{w * 8}" height="{h * 8}" '
           f'shape-rendering="crispEdges">\n' + '\n'.join(rects) + '\n</svg>\n')
    with open(path, 'w', encoding='utf-8', newline='\n') as f:
        f.write(svg)


def padded(img, size):
    """정수 배율로 최대한 키운 뒤 투명 여백으로 size×size에 맞춘다."""
    scale = size // img.width
    big = img.resize((img.width * scale, img.height * scale), Image.NEAREST)
    canvas = Image.new('RGBA', (size, size), (0, 0, 0, 0))
    canvas.alpha_composite(big, ((size - big.width) // 2, (size - big.height) // 2))
    return canvas


def save(img, *parts):
    path = os.path.join(OUT, *parts)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    img.save(path)
    return path


def main():
    c24, c16 = cat24(), cat16()
    t24, t16 = tile(c24), tile(c16, 16)

    # 원본 SVG (도트 1칸 = 1 단위)
    os.makedirs(os.path.join(OUT, 'svg'), exist_ok=True)
    to_svg(c24, os.path.join(OUT, 'svg', 'mascot.svg'))
    to_svg(t24, os.path.join(OUT, 'svg', 'icon.svg'))
    to_svg(t16, os.path.join(OUT, 'svg', 'icon-16.svg'))
    for variant, ch in WORD_COLORS.items():
        to_svg(horizontal(ch), os.path.join(OUT, 'svg', f'logo-horizontal-{variant}.svg'))

    # 마스코트(투명 배경): 24px 정수 배율
    for scale in (1, 2, 4, 8, 16):
        save(to_image(c24, scale), 'png', f'mascot-{24 * scale}.png')

    # 앱 아이콘: 작은 크기는 16px 도안, 그 이상은 24px 도안
    icon16 = to_image(t16)
    icon24 = to_image(t24)
    save(icon16, 'png', 'icon-16.png')
    save(to_image(t16, 2), 'png', 'icon-32.png')
    for size in (48, 72, 96, 144, 192, 384):          # 24의 정수 배수
        save(to_image(t24, size // 24), 'png', f'icon-{size}.png')
    for size in (64, 128, 256, 512):                  # 정수 배율 + 여백
        save(padded(icon24, size), 'png', f'icon-{size}.png')

    # 가로형 로고
    for variant, ch in WORD_COLORS.items():
        h = to_image(horizontal(ch))
        for scale in (1, 2, 4, 8):
            save(h.resize((h.width * scale, h.height * scale), Image.NEAREST),
                 'png', f'logo-horizontal-{variant}@{scale}x.png')

    # 파비콘 .ico (16·32는 16px 도안, 48은 24px 도안)
    ico_path = os.path.join(OUT, 'favicon.ico')
    to_image(t24, 2).save(ico_path, format='ICO', sizes=[(16, 16), (32, 32), (48, 48)],
                          append_images=[icon16, to_image(t16, 2)])
    # 모드 아이콘 (Fabric icon, NeoForge/Forge logoFile)
    mod_icon = os.path.join(OUT, '..', 'common', 'src', 'main', 'resources', 'assets', 'pawprint', 'icon.png')
    padded(icon24, 128).save(os.path.normpath(mod_icon))
    print('done:', OUT)


if __name__ == '__main__':
    main()
