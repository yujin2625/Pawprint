# Pawprint Text Blueprint (draft v1)

> 상태: v1 구현됨.
>
> AI(ChatGPT, Claude 등)나 사람이 텍스트로 청사진을 만들 수 있게 하는 형식이다.
>
> **게임 안에서 쓰는 법** (라이브러리 화면, 기본 키 `B`)
> 1. **AI 안내문 복사**: 아래 안내문에 현재 MC 버전과 설치된 모드 목록을 채워 클립보드에 복사한다.
>    AI 채팅에 붙여넣고 끝에 원하는 건물을 적는다.
> 2. AI가 준 JSON을 복사하고 **클립보드 가져오기**를 누른다. 코드 블럭(```)이나 앞뒤 설명이 섞여 있어도 된다.
>    오류가 나면 오류 내용이 클립보드에 복사되니, AI에게 그대로 붙여넣어 고쳐 달라고 하면 된다.
> 3. **블럭 목록 저장**: 게임에 있는 모든 블럭 ID와 한/영 이름을 `pawprint/block-list.txt`로 저장한다.
>    AI 채팅에 첨부하면 모드 블럭도 정확한 ID로 쓰게 할 수 있다.
> 4. **AI 형식 복사**: 고른 청사진을 이 형식(레이어만)으로 복사한다. AI에게 "이 건물을 이렇게 고쳐줘"라고 할 때 쓴다.
>
> `.pawprint.json` 파일을 `pawprint/blueprints/` 폴더에 직접 넣어도 라이브러리에 나타난다.
>
> **웹 편집기·데스크탑 앱에서 쓰는 법** (프로젝트 화면)
> 1. **AI 안내문 복사**: 같은 안내문에 기본 블럭 팩의 MC 버전과 모드 목록을 채워 복사한다. 블럭 팩이 없으면 바닐라 블럭만 쓰라고 적는다.
> 2. AI가 준 JSON을 복사하고 **클립보드에서 가져오기**를 누르거나 프로젝트 화면에서 Ctrl+V를 누른다. 새 프로젝트로 열린다.
>    오류가 나면 게임에서처럼 고쳐 달라는 요청이 클립보드에 복사된다.
> 3. 블럭은 기본 블럭 팩으로 확인한다: 팩에 없는 블럭은 경고와 함께 그대로 두고, 게임에서 내보낸 팩이면 속성 값도 게임처럼 검사한다.
>    블럭 목록 저장과 AI 형식 복사는 웹에 아직 없다.
>
> 이 형식의 도형(`box`, `walls`, `sphere` ...)은 편집 모드의 도형 도구와 같은 코드로 만들어진다.

---

## 1. Overview

A text blueprint is one JSON object. It describes blocks with two complementary tools:

- **operations**: shape commands (box, walls, line, sphere, ...). Best for large, regular parts.
- **layers**: character grids, one per Y level. Best for detailed parts (windows, doors, decoration).

Operations run first, in order; then layers are applied. Later writes overwrite earlier ones.

## 2. Coordinates

- `x` grows to the **east**, `y` grows **up**, `z` grows to the **south** (Minecraft's axes).
- Coordinates are integers relative to the blueprint. Negative values are allowed; on import the blueprint
  is shifted so its smallest corner becomes `(0, 0, 0)`.
- When the blueprint is placed, `(0, 0, 0)` lands on the placement origin. The player can rotate and mirror it
  afterwards, so "north" in the file is only the default facing.

## 3. Top-level fields

| Field | Required | Type | Meaning |
|---|---|---|---|
| `pawprint` | yes | integer | Format version. Must be `1`. |
| `name` | yes | string | Blueprint name shown in the library. |
| `description` | no | string | Free text. |
| `tags` | no | array of strings | Used for library filtering. |
| `palette` | no | object | Map from a single character to a block (see §4). |
| `operations` | no | array | Shape commands (see §5). |
| `layers` | no | object | Character grids (see §6). |

At least one of `operations` or `layers` must be present.

## 4. Blocks and the palette

A **block** is written like the `/setblock` command: `namespace:id[property=value,...]`.

- `minecraft:oak_planks`
- `minecraft:oak_stairs[facing=east,half=bottom]`
- `minecraft:oak_log[axis=x]`
- Properties that are left out use the block's default state.
- Modded blocks work the same way, e.g. `create:andesite_casing`.
- Block entity data (chest contents, sign text) is not supported in v1.
- The special value `air` means **this block must be removed / kept empty** (e.g. digging out a doorway).

The **palette** maps one character to a block, so layers stay readable:

```json
"palette": {
  "#": "minecraft:cobblestone",
  "P": "minecraft:oak_planks",
  "G": "minecraft:glass_pane",
  "D": "minecraft:oak_door[facing=south,half=lower]",
  "d": "minecraft:oak_door[facing=south,half=upper]",
  "_": "air"
}
```

Rules:

- Keys are exactly one character. Space (` `) and dot (`.`) are reserved and cannot be keys.
- In `operations`, `block` may be a palette key **or** a full block string. A value containing `:` or equal to
  `air` is read as a block string; anything else is a palette key.

## 5. Operations

Each operation is an object with a `shape` and the fields that shape needs. All positions are `[x, y, z]`.
Ranges are inclusive: `from: [0,0,0], to: [2,0,0]` covers three blocks.

| `shape` | Fields | Result |
|---|---|---|
| `single` | `at`, `block` | One block. |
| `line` | `from`, `to`, `block` | A straight line without gaps, also diagonal. |
| `box` | `from`, `to`, `block` | Filled cuboid. |
| `hollow_box` | `from`, `to`, `block` | Cuboid shell (all six faces). |
| `walls` | `from`, `to`, `block` | The four side walls of a cuboid, no floor or ceiling. |
| `sphere` | `center`, `radius`, `block` | Filled sphere. |
| `cylinder` | `base`, `radius`, `height`, `block` | Filled vertical cylinder; `base` is the center of its bottom layer. |

Example:

```json
"operations": [
  { "shape": "box",   "from": [0, 0, 0], "to": [8, 0, 6], "block": "#" },
  { "shape": "walls", "from": [0, 1, 0], "to": [8, 3, 6], "block": "P" },
  { "shape": "box",   "from": [1, 1, 1], "to": [7, 3, 5], "block": "air" }
]
```

## 6. Layers

```json
"layers": {
  "origin": [0, 1, 0],
  "grid": [
    [
      "PPPPPPPPP",
      "P.......P",
      "P.......P",
      "PPPPDPPPP"
    ],
    [
      "PPGGPGGPP",
      "P.......P",
      "P.......P",
      "PPPPdPPPP"
    ]
  ]
}
```

- `grid` is a list of **layers from bottom to top**. Layer `i` is at `y = origin.y + i`.
- Each layer is a list of **rows from north to south**. Row `j` is at `z = origin.z + j`.
- Each row is a string read **from west to east**. Character `k` is at `x = origin.x + k`.
- `.` and ` ` (space) mean "nothing here": the position is not part of the blueprint and is left as it is.
  Use a palette entry mapped to `air` to require an empty block.
- Rows may have different lengths; missing characters count as `.`.
- `origin` is optional and defaults to `[0, 0, 0]`.

## 7. Limits and errors

- At most 1,000,000 positions after all operations and layers.
- A single operation may cover at most 262,144 blocks (the same limit as the editor's shape tools).
- An unknown palette key is an error that names the operation index or the layer/row/column.
- An unknown block (e.g. from a mod that is not installed) is **kept** with its ID and shown in purple.
  The importer lists such blocks as warnings.

## 8. Complete example

```json
{
  "pawprint": 1,
  "name": "Small Oak Cabin",
  "description": "7x5 cabin with a door on the south side, windows, and a simple stair roof rising to the middle row.",
  "tags": ["house", "oak", "starter"],
  "palette": {
    "#": "minecraft:cobblestone",
    "L": "minecraft:oak_log[axis=y]",
    "P": "minecraft:oak_planks",
    "G": "minecraft:glass_pane",
    "D": "minecraft:oak_door[facing=south,half=lower,hinge=left]",
    "d": "minecraft:oak_door[facing=south,half=upper,hinge=left]",
    "S": "minecraft:oak_stairs[facing=north,half=bottom]",
    "s": "minecraft:oak_stairs[facing=south,half=bottom]",
    "_": "air"
  },
  "operations": [
    { "shape": "box",   "from": [0, 0, 0], "to": [6, 0, 4], "block": "#" },
    { "shape": "walls", "from": [0, 1, 0], "to": [6, 3, 4], "block": "P" },
    { "shape": "box",   "from": [1, 1, 1], "to": [5, 3, 3], "block": "_" },
    { "shape": "line",  "from": [0, 1, 0], "to": [0, 3, 0], "block": "L" },
    { "shape": "line",  "from": [6, 1, 0], "to": [6, 3, 0], "block": "L" },
    { "shape": "line",  "from": [0, 1, 4], "to": [0, 3, 4], "block": "L" },
    { "shape": "line",  "from": [6, 1, 4], "to": [6, 3, 4], "block": "L" }
  ],
  "layers": {
    "origin": [0, 1, 0],
    "grid": [
      [".......", ".......", ".......", ".......", "...D..."],
      ["..G.G..", "G.....G", ".......", "G.....G", "...d..."],
      [".......", ".......", ".......", ".......", "......."],
      ["sssssss", "sssssss", "PPPPPPP", "SSSSSSS", "SSSSSSS"]
    ]
  }
}
```

---

## AI에게 줄 안내문 (복사해서 사용)

```text
You are designing a Minecraft build for the Pawprint mod. Reply with ONE JSON object only, no prose.

Format "Pawprint Text Blueprint v1":
- Axes: x = east, y = up, z = south. Integer coordinates. Ranges are inclusive.
- Top level: {"pawprint": 1, "name": str, "description": str?, "tags": [str]?,
  "palette": {char: block}?, "operations": [op]?, "layers": {"origin": [x,y,z]?, "grid": [[row]]}?}
- Block strings use /setblock syntax: "minecraft:oak_stairs[facing=east,half=bottom]".
  "air" means the spot must be empty. Only use block IDs that exist in Minecraft <VERSION>
  (plus these mods: <MOD LIST or "none">).
- Operations run in order, then layers; later writes overwrite earlier ones. Shapes:
  single{at}, line{from,to}, box{from,to}, hollow_box{from,to}, walls{from,to},
  sphere{center,radius}, cylinder{base,radius,height}; each has "block" (palette char or block string).
- Layers: grid = list of layers bottom->top; each layer = list of rows north->south;
  each row = string west->east; one character per block; "." or space = nothing.
  Palette keys are single characters other than "." and space.
- Use operations for large regular parts and layers for details (doors, windows, decoration).
- Set facing/half/axis properties for stairs, doors, logs, slabs so the build looks right
  when its front faces south.

Build request: <DESCRIBE WHAT TO BUILD, SIZE, STYLE>
```

---

### 모드 블럭 목록
모드("AI 안내문 복사")와 웹(프로젝트 → "AI 안내문 복사")은 `Build request:` 바로 앞에 모드 블럭 ID를 네임스페이스별로 붙인다
(`create: andesite_casing, …`). 바닐라 블럭과 액체 블럭은 넣지 않는다.

**웹·데스크탑**: 복사 창에서 블럭 팩과 모드를 고른다.
- 모드마다 체크박스와 블럭 수가 나오고, 고른 모드의 블럭은 **전부** 넣는다(상한 없음). 해제한 모드는 모드 이름 목록에서도 빠져 AI가 쓰지 않는다.
- 고른 모드는 팩마다 기억한다(처음과 새로 생긴 모드는 선택된 상태).
- 크기(글자 수, 약 4자당 1토큰)를 보여 주고, 약 2만 토큰을 넘으면 경고, 약 6만 토큰을 넘으면 더 강한 경고를 띄운다. 복사는 막지 않는다.
- AI의 답을 가져올 때는 마지막으로 안내문을 복사한 팩으로 블럭을 검사한다(그 팩이 지워졌으면 기본 팩).
- 확인용: `npx vite-node tools/check-ai-prompt.ts <file.pawpack> [namespace,…]`가 크기를 보여 준다.

**모드(게임 안)**: 고르는 화면이 없으므로 상한 3,000개(약 1.5만 토큰)를 둔다(`format/text/ModdedBlockList`).
- 넘으면 네임스페이스마다 같은 몫을 주고(블럭이 적은 모드는 전부), 아이템이 있는 블럭을 먼저 채운다.
  남길 ID는 목록 전체에서 고르게 뽑는다(앞에서부터 자르면 이름순 목록이 a~d에서 끊긴다). 잘린 줄에는 `(+N more not listed)`를 붙이고,
  이때 AI에게 맞는 모드 블럭이 없으면 바닐라를 쓰라고 한다.
- 잘렸을 때 상태줄에서 웹 앱으로 고른 모드를 전부 넣는 방법을 안내한다.
- 전체 목록이 필요하면 "블럭 목록 내보내기"(`pawprint/block-list.txt`)를 AI 채팅에 첨부해도 된다.

## 구현 메모
- 읽기: `format/text/TextBlueprintReader` (JSON → `Blueprint`). 도형은 편집 도구와 같은 `shape.Shape`를 쓴다.
- 쓰기: `format/text/TextBlueprintWriter` (레이어만, 쓰인 블럭마다 문자 하나).
- 좌표 범위: |x|, |z| ≤ 100,000, |y| ≤ 1,000 (내부 위치 압축 형식의 한계).
- 블럭 상태가 잘못된 경우(있는 블럭인데 속성이 틀림)는 오류, 블럭 자체가 없는 경우는 경고 후 ID를 보존한다.
