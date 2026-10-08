# `.pawprint` 청사진 형식

> 모드(Java)와 웹(TypeScript)이 함께 따르는 명세. 이 문서와 다르게 구현하지 않는다.
> 최종 수정: 2026-10-08

---

## 1. 개요

- `.pawprint` 파일은 **zip**이다. 압축 방식은 deflate 또는 store.
- 안에 든 항목:

| 이름 | 필수 | 내용 |
|---|---|---|
| `meta.json` | 예 | 정보 (UTF-8 JSON) |
| `blueprint.nbt` | 예 | 블럭 데이터 (gzip으로 압축한 NBT) |
| `thumbnail.png` | 아니오 | 미리보기 그림. 웹이 저장할 때 넣는다. 모드는 읽지 않고, 다시 저장할 때 버린다. |

- 모르는 항목은 무시한다.
- 형식 버전(`meta.json`의 `format`): **1**(레이어 없음), **2**(레이어). 읽는 쪽은 자기가 아는 버전보다 큰 파일을 "너무 새 형식"이라며 거부한다.

### 1.1 크기 제한 (읽을 때)

| 대상 | 최대 |
|---|---|
| `meta.json` | 1 MiB |
| `blueprint.nbt` (압축된 상태) | 64 MiB |
| NBT를 풀었을 때 메모리 | 512 MiB |
| 블럭 수 + 제거 칸 수 | 16,000,000 |
| 팔레트 항목 수 | 1,048,576 |
| `thumbnail.png` | 2 MiB |

넘으면 읽기를 거부한다.

---

## 2. 좌표

- 모든 좌표는 청사진의 **최소 모서리를 (0, 0, 0)으로 한 상대 좌표**이고 음수가 없다.
- 크기 `Size = [sx, sy, sz]`는 `최대 좌표 + 1`이다.
- 좌표는 64비트 정수 하나로 묶어 저장한다(마인크래프트 `BlockPos.asLong`과 같음):

```
비트:  63 ........ 38 | 37 ........ 12 | 11 ..... 0
       x (26비트)       z (26비트)        y (12비트)

packed = ((x & 0x3FFFFFF) << 38) | ((z & 0x3FFFFFF) << 12) | (y & 0xFFF)
x = packed >> 38              (부호 있는 26비트)
z = (packed << 26) >> 38      (부호 있는 26비트)
y = (packed << 52) >> 52      (부호 있는 12비트)
```

- 웹(TypeScript)은 `BigInt`로 묶고 푼다. NBT의 long 배열은 `BigInt64Array`로 읽는다.

---

## 3. 블럭 상태 문자열

- 블럭은 **상태 문자열**로 저장한다: `minecraft:oak_stairs[facing=east,half=bottom,shape=straight,waterlogged=false]`
- 형식: `<블럭 ID>` 또는 `<블럭 ID>[<속성>=<값>,...]`
  - 블럭 ID에 이름공간이 없으면 `minecraft:`로 본다.
  - 속성 순서는 상관없다. 같은 상태로 취급한다. 저장할 때는 마인크래프트 `BlockStateParser.serialize`가 내는 순서(블럭 정의 순서)를 따른다.
  - 빠진 속성은 그 블럭의 기본값이다.
- 지금 게임(또는 웹에서 연 블럭 팩)에 없는 블럭이어도 **문자열을 그대로 보존**한다. 저장할 때 바꾸거나 버리지 않는다.

---

## 4. `meta.json`

```json
{
  "format": 2,
  "id": "6f1c0b5e-3d1a-4c55-9a37-0d1f0e2b9a10",
  "name": "Small cabin",
  "description": "",
  "author": "yujin2625",
  "tags": ["house"],
  "created": "2026-10-08T07:10:00Z",
  "modified": "2026-10-08T07:42:11Z",
  "mcVersion": "1.21.1",
  "dataVersion": 3955,
  "size": [7, 5, 5],
  "blockCount": 152,
  "removalCount": 0,
  "mods": ["minecraft"],
  "blocks": ["minecraft:glass", "minecraft:oak_planks"],
  "origin": {"server": "play.example.com", "dimension": "minecraft:overworld", "pos": [120, 64, -340]},

  "layers": [ ... ],
  "layerOrder": [ ... ],
  "packHint": {"id": "uuid", "name": "Better MC 5 (1.21.1)"}
}
```

| 필드 | 형식 | 버전 | 설명 |
|---|---|---|---|
| `format` | 정수 | 1 | 형식 버전 |
| `id` | 문자열 | 1 | UUID. 새 청사진마다 새로 만든다 |
| `name`, `description`, `author` | 문자열 | 1 | |
| `tags` | 문자열 배열 | 1 | 라이브러리 분류 |
| `created`, `modified` | 문자열 | 1 | ISO-8601 UTC 시각. 문자열 정렬 = 시간 정렬 |
| `mcVersion` | 문자열 | 1 | 저장한 게임 버전. 웹은 열린 팩의 버전을 쓰고, 팩이 없으면 원래 값을 유지 |
| `dataVersion` | 정수 | 1 | 마인크래프트 데이터 버전. 위와 같은 규칙 |
| `size` | 정수 3개 | 1 | `blueprint.nbt`의 `Size`와 같음 |
| `blockCount`, `removalCount` | 정수 | 1 | 블럭 수, 제거 칸 수 |
| `mods` | 문자열 배열 | 1 | 팔레트에 나오는 이름공간 (정렬) |
| `blocks` | 문자열 배열 | 1 | 팔레트에 나오는 블럭 ID, 상태 제외 (정렬). 라이브러리 검색용 |
| `origin` | 객체 또는 없음 | 1 | 만든 위치. 같은 자리에 다시 놓을 때 쓴다 |
| `layers` | 배열 | 2 | 레이어와 그룹 목록 (5장) |
| `layerOrder` | 정수 배열 | 2 | 맨 위 단계의 표시 순서 (5장) |
| `packHint` | 객체 또는 없음 | 2 | 웹에서 마지막에 쓴 블럭 팩. 힌트일 뿐 없어도 된다 |

- `size`, `blockCount`, `removalCount`, `mods`, `blocks`는 **블럭 데이터에서 계산하는 값**이다. 저장할 때마다 다시 계산한다.
- 모르는 필드는 읽을 때 무시하고, 가능하면 다시 저장할 때 보존한다.

---

## 5. 레이어 (format 2)

### 5.1 `meta.json`의 `layers`

```json
"layers": [
  {"id": 0, "name": "Default", "color": "#7FB3FF", "visible": true, "locked": false, "parent": null},
  {"id": 1, "name": "Living room", "color": "#FFB347", "visible": true, "locked": false, "parent": 2},
  {"id": 2, "name": "Floor 1", "group": true, "collapsed": false, "parent": null, "children": [1]}
],
"layerOrder": [2, 0]
```

| 필드 | 형식 | 설명 |
|---|---|---|
| `id` | 정수 ≥ 0 | 파일 안에서 유일. 블럭이 이 번호로 레이어를 가리킨다 |
| `name` | 문자열 | |
| `color` | `#RRGGBB` | 색 태그 |
| `visible`, `locked` | 불 | 웹 편집기의 보임·잠금. 모드는 보존만 한다 |
| `parent` | 정수 또는 null | 속한 그룹의 `id`. 맨 위 단계면 null |
| `group` | 불 (없으면 false) | 그룹(폴더)이면 true. 그룹에는 블럭이 들어가지 않는다 |
| `collapsed` | 불 (그룹만) | 접힘 상태 |
| `children` | 정수 배열 (그룹만) | 그룹 안의 표시 순서 (위 → 아래) |

- `layerOrder`: 맨 위 단계(`parent`가 null)의 표시 순서, 위 → 아래.
- **레이어 0은 항상 있다.** 레이어 0은 그룹이 아니다. 이름은 바꿀 수 있지만 지울 수 없다.

### 5.2 `blueprint.nbt`의 레이어 배열
- `BlockLayers`: int 배열. `Positions`와 같은 길이, 같은 순서. 각 블럭의 레이어 `id`.
- `RemovalLayers`: int 배열. `Removals`와 같은 길이, 같은 순서.

### 5.3 규칙
- 한 칸에는 블럭(또는 제거 표시)이 하나뿐이고, 레이어도 하나다. 다른 레이어의 칸에 블럭을 놓으면 그 칸이 현재 레이어로 옮겨진다.
- 읽을 때:
  - format 1이면 모든 블럭과 제거 칸을 레이어 0으로 본다. `layers`가 없으면 `[{"id":0,"name":"Default",...}]`로 채운다.
  - 레이어 배열 길이가 맞지 않거나, 없는 `id`·그룹 `id`를 가리키면 그 칸은 레이어 0으로 본다(파일 전체를 거부하지 않는다).
  - `layerOrder`·`children`에 빠진 레이어는 맨 아래에 붙인다. 순환하는 `parent`는 끊어 맨 위 단계로 올린다.
- 저장할 때:
  - **레이어가 레이어 0 하나뿐이면 format 1로 저장한다**(옛 모드에서도 열리게). `layers`, `layerOrder`, `BlockLayers`, `RemovalLayers`를 쓰지 않는다.
  - 그 밖에는 format 2로 저장한다.
- 모드는 레이어를 해석하지 않고 **보존만** 한다. 모드에서 블럭을 추가하면 레이어 0, 바꾸기(블럭 교체)는 원래 칸의 레이어를 유지한다.
- 다른 형식(`.litematic`, `.schem`, `.nbt`)으로 내보내면 레이어는 사라진다.

---

## 6. `blueprint.nbt`

gzip으로 압축한 NBT. 최상위는 이름 없는 compound(마인크래프트 `NbtIo.writeCompressed`와 같음).

| 태그 | NBT 형식 | 버전 | 설명 |
|---|---|---|---|
| `DataVersion` | int | 1 | `meta.json`의 `dataVersion`과 같음 |
| `Size` | int 배열 [3] | 1 | |
| `Palette` | 문자열 list | 1 | 상태 문자열 (3장). 중복 없음 |
| `Positions` | long 배열 | 1 | 묶은 좌표 (2장) |
| `States` | int 배열 | 1 | `Positions`와 같은 길이. 팔레트 번호 |
| `Removals` | long 배열 | 1 | 반드시 비워야 하는 칸 (묶은 좌표) |
| `BlockLayers` | int 배열 | 2 | 5.2 |
| `RemovalLayers` | int 배열 | 2 | 5.2 |

- `Positions`와 `States`의 길이가 다르거나, 팔레트 번호가 범위를 벗어나면 파일을 거부한다.
- 같은 좌표가 `Positions`와 `Removals`에 함께 있으면 `Positions`가 이긴다.
- 블럭 순서에 의미는 없다.
- 공기(`minecraft:air`)는 블럭으로 저장하지 않는다. 비워야 할 칸은 `Removals`로 저장한다.

---

## 7. 공유 문자열 (`PAW1:`)

채팅에 붙여 넣는 한 줄 텍스트.

```
PAW1:<Base64url(gzip(NBT))>
```

- Base64는 URL-safe 알파벳(`-`, `_`)이고 `=` 채움 없음.
- NBT는 6장과 같고 다음 태그를 더한다: `Name`(문자열), `Description`(문자열), `Tags`(문자열 list).
- 레이어 태그(`BlockLayers`, `RemovalLayers`)와 `Layers` 문자열(아래)은 넣을 수 있다. 다만 공유 문자열에는 썸네일·작성자·출처가 없다.
  - format 2를 공유할 때는 `Layers` 태그에 `meta.json`의 `{"layers":…, "layerOrder":…}`를 JSON 문자열로 넣는다. 이 태그가 없으면 레이어 0 하나로 읽는다.
- 읽을 때: 문자열 안 어디든 `PAW1:`을 찾아 그 뒤부터 공백 전까지를 쓴다. 최대 8,388,608자.
- 받은 쪽에서 새 `id`, 받은 사람을 `author`, 지금 시각을 `created`·`modified`로 정한다.

---

## 8. 버전 정책

- 필드를 **추가**만 하는 변경은 형식 버전을 올리지 않는다. 읽는 쪽은 모르는 필드를 무시한다.
- 옛 버전이 잘못 읽을 수 있는 변경(뜻이 바뀌는 변경, 레이어처럼 무시하면 정보가 사라지는 변경)은 형식 버전을 올린다.
- 이 문서를 바꿀 때 모드와 웹 양쪽 구현과 테스트를 같이 고친다.

---

## 9. 버전 브랜치와 웹

- 모드는 마인크래프트 버전마다 브랜치가 있지만(main = 1.21.1, `mc/1.20.1` …), **형식은 모든 브랜치에서 같다.**
- 웹은 마인크래프트 버전과 무관한 **하나의 코드**이고 `main`에만 있다. 버전 차이는 블럭 팩(블럭 목록·모양·텍스처)과 청사진의 상태 문자열로 들어온다.
