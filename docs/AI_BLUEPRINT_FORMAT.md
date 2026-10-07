# Pawprint Text Blueprint (draft v1)

> 상태: 초안. 아직 구현되지 않았다.
>
> AI(ChatGPT, Claude 등)나 사람이 텍스트로 청사진을 만들 수 있게 하는 형식이다.
> 이 문서 아래쪽의 **AI에게 줄 안내문**을 그대로 복사해 AI에게 주면 된다.
> 결과 JSON은 라이브러리의 "클립보드에서 가져오기"로 붙여넣거나, `.pawprint.json` 파일로
> `pawprint/blueprints/` 폴더에 넣으면 일반 청사진처럼 쓸 수 있다.
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

## 구현 계획 (메모)
- `format/text/TextBlueprintReader`: JSON → `Blueprint`. 도형은 `shape.Shape`를 그대로 쓴다.
- 라이브러리: `.pawprint.json` 파일도 목록에 표시하고, 열 때 변환한다.
- 라이브러리 버튼: "클립보드에서 가져오기", "블럭 목록 내보내기"(현재 게임의 모든 블럭 ID와 한/영 이름을
  텍스트로 저장해서, 모드 블럭까지 AI에게 알려줄 수 있게).
- 반대 방향: 청사진을 이 형식으로 내보내기 (AI에게 "이 건물을 고쳐줘"라고 할 수 있게).
  큰 청사진은 레이어가 길어지므로 크기 경고를 둔다.
