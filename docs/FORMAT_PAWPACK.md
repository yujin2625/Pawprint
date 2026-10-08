# `.pawpack` 블럭 팩 형식

> 모드(Java)와 웹(TypeScript)이 함께 따르는 명세.
> 최종 수정: 2026-10-08

블럭 팩은 웹 편집기가 블럭 목록, 이름, 모양, 텍스처를 알기 위해 쓰는 파일이다. 각 사용자가 **자기 게임에서 만들어 자기 브라우저에서만 쓴다**(Mojang·모드 제작자의 에셋을 웹사이트가 배포하지 않기 위해).

---

## 1. 개요

- `.pawpack` 파일은 **zip**이다.

```
example.pawpack
├─ pack.json              필수
├─ blocks.json            필수
├─ lang/<언어>.json        하나 이상 (en_us 권장)
├─ colors.json            선택
├─ icons.png, icons.json  선택 (둘 다 있거나 둘 다 없음)
└─ assets/<이름공간>/...   리소스팩과 같은 배치
```

- 모르는 항목은 무시한다.
- 형식 버전(`pack.json`의 `format`)은 **1**. 읽는 쪽은 더 큰 버전을 거부한다.
- 모든 JSON은 UTF-8.

### 1.1 크기 제한 (읽을 때)

| 대상 | 최대 |
|---|---|
| zip 전체 | 1 GiB |
| 항목 하나를 풀었을 때 | 64 MiB |
| 항목 수 | 200,000 |
| `blocks.json` 블럭 수 | 100,000 |

압축 폭탄을 막기 위해 푼 크기를 세면서 읽는다. 항목 이름에 `..`, `\`, 맨 앞 `/`가 있으면 그 항목은 무시한다.

---

## 2. `pack.json`

```json
{
  "format": 1,
  "id": "0b9e6c1e-6a52-4a8e-9a2f-2b7a4d0e1c33",
  "name": "Better MC 5 (1.21.1)",
  "created": "2026-10-08T12:00:00Z",
  "generator": "pawprint-mod 0.2.0",
  "source": "mod-export",
  "mcVersion": "1.21.1",
  "dataVersion": 3955,
  "loader": "neoforge",
  "mods": [{"id": "create", "name": "Create", "version": "6.0.0"}],
  "resourcePacks": ["vanilla", "file/Faithful 32x"],
  "languages": ["en_us", "ko_kr"],
  "blockCount": 4210,
  "propertiesComplete": true
}
```

| 필드 | 형식 | 필수 | 설명 |
|---|---|---|---|
| `format` | 정수 | 예 | 1 |
| `id` | 문자열 | 예 | UUID. 같은 팩의 새 버전으로 교체할 때는 웹이 사용자에게 묻는다 |
| `name` | 문자열 | 예 | 표시 이름 |
| `created` | 문자열 | 예 | ISO-8601 UTC |
| `generator` | 문자열 | 아니오 | 만든 프로그램과 버전 |
| `source` | 문자열 | 예 | `mod-export`(모드), `vanilla-jar`(웹·앱이 jar로 만듦) |
| `mcVersion`, `dataVersion` | 문자열, 정수 | 예 | 게임 버전 |
| `loader` | 문자열 | 아니오 | `neoforge`, `forge`, `fabric`, `vanilla` |
| `mods` | 배열 | 아니오 | 블럭을 가진 모드 목록 (`minecraft` 제외) |
| `resourcePacks` | 문자열 배열 | 아니오 | 내보낼 때 적용돼 있던 리소스팩 (위가 우선) |
| `languages` | 문자열 배열 | 예 | `lang/`에 들어 있는 언어 코드 |
| `blockCount` | 정수 | 예 | `blocks.json` 항목 수 |
| `propertiesComplete` | 불 | 예 | 속성 목록이 게임 레지스트리에서 온 것이면 true. jar에서 추론했으면 false |

---

## 3. `blocks.json`

배열. 항목 하나가 블럭 하나다.

```json
[
  {
    "id": "minecraft:oak_stairs",
    "properties": {
      "facing": ["north", "south", "west", "east"],
      "half": ["top", "bottom"],
      "shape": ["straight", "inner_left", "inner_right", "outer_left", "outer_right"],
      "waterlogged": ["true", "false"]
    },
    "default": {"facing": "north", "half": "bottom", "shape": "straight", "waterlogged": "false"},
    "item": "minecraft:oak_stairs",
    "renderLayer": "solid",
    "renderShape": "model",
    "tint": null,
    "tabs": ["minecraft:building_blocks"],
    "order": 812
  },
  {
    "id": "minecraft:oak_leaves",
    "properties": {"distance": ["1","2","3","4","5","6","7"], "persistent": ["true","false"], "waterlogged": ["true","false"]},
    "default": {"distance": "7", "persistent": "false", "waterlogged": "false"},
    "item": "minecraft:oak_leaves",
    "renderLayer": "cutout_mipped",
    "renderShape": "model",
    "tint": {"kind": "foliage", "color": "#77AB2F"},
    "tabs": ["minecraft:natural_blocks"],
    "order": 2034
  }
]
```

| 필드 | 형식 | 필수 | 설명 |
|---|---|---|---|
| `id` | 문자열 | 예 | 블럭 ID (이름공간 포함) |
| `properties` | 객체 | 예 | 속성 이름 → 가능한 값 목록(게임 정의 순서). 속성이 없으면 `{}` |
| `default` | 객체 | 예 | 기본 상태의 속성 값 |
| `item` | 문자열 또는 null | 예 | 이 블럭을 놓는 아이템 ID. 없으면 null(재료 목록에서 "아이템 없음"으로 따로 셈) |
| `renderLayer` | 문자열 | 예 | `solid`, `cutout`, `cutout_mipped`, `translucent` |
| `renderShape` | 문자열 | 예 | `model`(블럭 모델로 그림), `entity`(상자·표지판처럼 따로 그림 → 웹은 대체 표시), `invisible`(공기 등) |
| `tint` | 객체 또는 null | 예 | 색을 입히는 블럭. 아래 |
| `tabs` | 문자열 배열 | 예 | 크리에이티브 탭 ID. 팔레트 분류용. 없으면 `[]` |
| `order` | 정수 | 아니오 | 팔레트 정렬 순서(크리에이티브 탭 안 순서 기준). 없으면 ID 순 |
| `fluid` | 문자열 | 아니오 | 물·용암처럼 액체 블럭이면 액체 ID |

**`tint`**

```json
{"kind": "grass" | "foliage" | "water" | "constant" | "other", "color": "#RRGGBB", "byState": {"power=0": "#4B0000"}}
```

- `color`: 평원 생물군계, 기본 상태 기준 색. 웹은 기본적으로 이 색을 쓴다.
- `byState`(선택): 상태에 따라 색이 바뀌는 블럭(레드스톤 선의 세기 등). 키는 `속성=값`(여럿이면 쉼표로 이음).
- 모델 면의 `tintindex`가 0 이상인 면에만 곱한다.

---

## 4. `lang/<언어>.json`

```json
{"minecraft:oak_stairs": "Oak Stairs", "create:andesite_casing": "Andesite Casing"}
```

- 키는 **블럭 ID**, 값은 그 언어의 표시 이름. (번역 키가 아님)
- 언어 코드는 마인크래프트 형식(`en_us`, `ko_kr`).
- 표시 순서: 웹 UI 언어 → `en_us` → 블럭 ID. 검색은 팩에 있는 모든 언어 이름과 ID로 한다.

---

## 5. `colors.json` (선택)

```json
{"grass": "#91BD59", "foliage": "#77AB2F", "water": "#3F76E4"}
```

생물군계 색을 쓰는 블럭의 기본 색. 없으면 위 값을 쓴다.

---

## 6. 아이콘 (선택)

- `icons.png`: 아이템 아이콘을 격자로 모은 그림.
- `icons.json`:

```json
{"cell": 32, "columns": 64, "icons": {"minecraft:oak_stairs": 0, "minecraft:stone": 1}}
```

- 키는 **블럭 ID**, 값은 칸 번호(왼쪽 위부터 오른쪽으로, 줄 바꿈). 칸 위치 = `(i % columns, i / columns) × cell`.
- 아이콘이 없는 블럭은 웹이 모델로 작은 그림을 만들거나 색 상자로 보여준다.

---

## 7. `assets/`

- 리소스팩과 같은 배치: `assets/<이름공간>/blockstates/*.json`, `assets/<이름공간>/models/**.json`, `assets/<이름공간>/textures/**.png`(+ `.png.mcmeta`).
- **블럭의 blockstate가 참조하는 모델, 그 모델의 부모, 그 모델들이 참조하는 텍스처만** 넣는다. 블럭이 아닌 아이템 모델·소리·언어 파일 등은 넣지 않는다.
- 내보낼 때 리소스팩이 적용돼 있었으면, 리소스팩이 덮어쓴 파일이 들어간다(게임에서 보이는 그대로).
- 웹은 마인크래프트 버전별 코드가 없다. blockstate·모델 JSON은 다음처럼 **필드 유무로** 읽는다:
  - blockstate: `variants` 또는 `multipart`. 변형 값은 객체 하나 또는 가중치 배열(배열이면 첫째를 쓴다).
  - 모델: `parent`, `textures`, `elements`, `ambientocclusion`, 면의 `uv`·`rotation`·`cullface`·`tintindex`, 요소의 `rotation`·`shade`. 모르는 필드는 무시한다.
  - 모델이 없는 블럭(`renderShape`가 `entity`), 해석에 실패한 블럭은 대체 표시를 쓴다.

---

## 8. 바닐라 jar로 만든 팩 (`source: "vanilla-jar"`)

- 사용자가 고른 `<버전>.jar`에서 만든다. jar 안의 `version.json`에서 `mcVersion`·`dataVersion`을 읽는다.
- 블럭 목록과 속성은 `assets/minecraft/blockstates/*.json`에서 **추론**한다:
  - `variants`의 키(`facing=east,half=top`)와 `multipart`의 `when` 조건에서 속성 이름과 값을 모은다.
  - 기본 상태는 각 속성의 첫 값으로 둔다. 실제 게임 기본값과 다를 수 있다.
  - 모델에 쓰이지 않는 속성(예: `waterlogged`)은 빠진다.
  - 그래서 `propertiesComplete: false`.
- 언어는 jar에 든 `en_us`만. 데스크탑 앱은 `.minecraft/assets/indexes`·`objects`에서 다른 언어도 찾을 수 있다.
- `item`은 같은 ID의 아이템 모델이 있으면 그 ID, 없으면 null. `tabs`는 비우고, `tint`는 알려진 바닐라 블럭 목록(웹에 내장한 표)으로 채운다.

---

## 9. 버전 정책

`.pawprint`와 같다. 필드 추가는 버전을 올리지 않고, 읽는 쪽은 모르는 필드를 무시한다.
