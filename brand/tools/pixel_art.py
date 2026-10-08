"""Pawprint 마스코트 "청사진 고양이" 도트 데이터. 한 글자 = 한 픽셀, '.' = 투명."""
from PIL import Image

PAL = {
    'o': (0x41, 0x24, 0x02), 'a': (0xEF, 0x9F, 0x27), 'A': (0xBA, 0x75, 0x17), 'p': (0xFA, 0xC7, 0x75),
    'c': (0xFA, 0xEE, 0xDA), 'k': (0x2C, 0x2C, 0x2A), 'w': (0xFF, 0xFF, 0xFF), 'n': (0xD4, 0x53, 0x7E),
    'R': (0x0C, 0x44, 0x7C), 'U': (0x18, 0x5F, 0xA5), 'u': (0x37, 0x8A, 0xDD), 'g': (0x85, 0xB7, 0xEB),
    's': (0x04, 0x2C, 0x53), 'B': (0x07, 0x1B, 0x30), 'b': (0x13, 0x33, 0x57),
}
N = 24


def blank(n=N):
    return [['.'] * n for _ in range(n)]


def put(g, x, y, ch):
    if 0 <= y < len(g) and 0 <= x < len(g[0]):
        g[y][x] = ch


def hline(g, x1, x2, y, ch):
    for x in range(x1, x2 + 1):
        put(g, x, y, ch)


def mirror_put(g, x, y, ch):
    put(g, x, y, ch)
    put(g, len(g[0]) - 1 - x, y, ch)


def cat24():
    g = blank()
    # Ears (left, mirrored)
    mirror_put(g, 6, 2, 'o')
    for x, ch in [(5, 'o'), (6, 'a'), (7, 'o')]:
        mirror_put(g, x, 3, ch)
    for x, ch in [(5, 'o'), (6, 'a'), (7, 'p'), (8, 'o')]:
        mirror_put(g, x, 4, ch)
    for x, ch in [(5, 'o'), (6, 'a'), (7, 'p'), (8, 'p')]:
        mirror_put(g, x, 5, ch)
    hline(g, 9, 14, 5, 'o')
    # Head
    for y in range(6, 13):
        put(g, 5, y, 'o')
        put(g, 18, y, 'o')
        hline(g, 6, 17, y, 'a')
    for y in range(7, 12):
        put(g, 17, y, 'A')          # shade on the right
    hline(g, 6, 17, 12, 'A')
    # Forehead stripes
    put(g, 11, 6, 'A'); put(g, 12, 6, 'A'); put(g, 10, 7, 'A'); put(g, 13, 7, 'A')
    # Eyes with shine
    for x in (8, 9, 14, 15):
        put(g, x, 8, 'k'); put(g, x, 9, 'k')
    put(g, 8, 8, 'w'); put(g, 14, 8, 'w')
    # Muzzle, nose, cheeks
    hline(g, 10, 13, 10, 'c'); hline(g, 10, 13, 11, 'c')
    put(g, 11, 10, 'n'); put(g, 12, 10, 'n')
    put(g, 10, 11, 'A'); put(g, 13, 11, 'A')
    put(g, 7, 10, 'p'); put(g, 16, 10, 'p')
    # Scroll paper
    for y in range(14, 20):
        hline(g, 4, 19, y, 'U')
    for x in (7, 10, 13, 16):
        for y in range(15, 19):
            put(g, x, y, 'u')
    for y in (16, 18):
        hline(g, 5, 18, y, 'u')
    hline(g, 2, 21, 13, 'c')       # top edge of the paper
    hline(g, 2, 21, 20, 'R')       # bottom edge
    # Rolls at both ends
    for y in range(13, 21):
        put(g, 2, y, 'R'); put(g, 3, y, 'u')
        put(g, 20, y, 'u'); put(g, 21, y, 'R')
    put(g, 2, 13, 'c'); put(g, 21, 13, 'c')
    put(g, 3, 16, 'c'); put(g, 20, 16, 'c')
    # Shadow under the scroll
    hline(g, 3, 20, 21, 's')
    # Paws over the paper edge (outlined)
    for x0 in (7, 14):
        hline(g, x0 - 1, x0 + 3, 12, 'o')
        for y in (13, 14):
            put(g, x0 - 1, y, 'o'); put(g, x0 + 3, y, 'o')
            hline(g, x0, x0 + 2, y, 'c')
        hline(g, x0, x0 + 2, 15, 'o')
        put(g, x0 + 1, 14, 'p')    # toe gap
    return g


def cat16():
    g = blank(16)
    for x in (3, 12):
        put(g, x, 1, 'o')
    for x0 in (2, 11):
        put(g, x0, 2, 'o'); put(g, x0 + 1, 2, 'a'); put(g, x0 + 2, 2, 'o')
    put(g, 2, 3, 'o'); put(g, 3, 3, 'a'); put(g, 4, 3, 'p')
    put(g, 13, 3, 'o'); put(g, 12, 3, 'a'); put(g, 11, 3, 'p')
    hline(g, 5, 10, 3, 'o')
    for y in range(4, 9):
        put(g, 2, y, 'o'); put(g, 13, y, 'o')
        hline(g, 3, 12, y, 'a')
    hline(g, 3, 12, 8, 'A')
    put(g, 7, 4, 'A'); put(g, 8, 4, 'A')
    for x in (5, 10):
        put(g, x, 5, 'k'); put(g, x, 6, 'k')
    put(g, 5, 5, 'w'); put(g, 10, 5, 'w')
    hline(g, 6, 9, 7, 'c')
    put(g, 7, 7, 'n'); put(g, 8, 7, 'n')
    for y in range(9, 13):
        hline(g, 2, 13, y, 'U')
    hline(g, 1, 14, 9, 'g')
    hline(g, 3, 12, 11, 'u')
    for x in (6, 9):
        put(g, x, 10, 'u'); put(g, x, 12, 'u')
    for y in range(9, 14):
        put(g, 1, y, 'R'); put(g, 14, y, 'R')
        put(g, 2, y, 'u'); put(g, 13, y, 'u')
    hline(g, 1, 14, 13, 'R')
    put(g, 1, 9, 'g'); put(g, 14, 9, 'g')
    hline(g, 2, 13, 14, 's')
    for x0 in (4, 10):
        for y in (8, 9):
            put(g, x0 - 1, y, 'o'); put(g, x0 + 2, y, 'o')
            put(g, x0, y, 'c'); put(g, x0 + 1, y, 'c')
        put(g, x0, 10, 'o'); put(g, x0 + 1, 10, 'o')
    return g


def tile(content, n=N, dots=True):
    g = blank(n)
    for y in range(n):
        hline(g, 0, n - 1, y, 'B')
    if dots:
        for y in range(3, n, 4):
            for x in range(3, n, 4):
                put(g, x, y, 'b')
    for y in range(n):
        for x in range(n):
            if content[y][x] != '.':
                g[y][x] = content[y][x]
    for x, y in [(0, 0), (1, 0), (0, 1)]:   # rounded corners
        for cx, cy in [(x, y), (n - 1 - x, y), (x, n - 1 - y), (n - 1 - x, n - 1 - y)]:
            g[cy][cx] = '.'
    return g


def to_image(g, scale=1):
    n = len(g)
    img = Image.new('RGBA', (len(g[0]), n), (0, 0, 0, 0))
    for y in range(n):
        for x in range(len(g[0])):
            ch = g[y][x]
            if ch != '.':
                img.putpixel((x, y), PAL[ch] + (255,))
    return img.resize((img.width * scale, img.height * scale), Image.NEAREST)

