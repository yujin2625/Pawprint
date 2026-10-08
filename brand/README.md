# Pawprint 브랜드 에셋

마스코트 "청사진 고양이": 청사진 두루마리 위로 고개를 내민 호박색 도트 고양이.
모든 파일은 `tools/make_brand.py`로 생성됩니다. 손으로 고치지 말고 도트 데이터(`tools/pixel_art.py`)를 고친 뒤 다시 생성하세요.

```bash
python brand/tools/make_brand.py
```

## 파일

| 파일 | 용도 |
|---|---|
| `favicon.ico` | 웹 파비콘 (16·32는 16px 전용 도안, 48은 24px 도안) |
| `png/icon-16.png`, `icon-32.png` | 작은 아이콘 (16px 전용 도안) |
| `png/icon-48` ~ `icon-384.png` | 앱 아이콘, 24px 도안을 정수 배율로 확대 |
| `png/icon-64·128·256·512.png` | 24의 배수가 아닌 크기: 정수 배율 + 투명 여백 |
| `png/mascot-*.png` | 배경 없는 마스코트 (소개 화면, 빈 화면, 오류 화면) |
| `png/logo-horizontal-dark@Nx.png` | 가로형 로고, 어두운 배경용 (PRINT = 크림색) |
| `png/logo-horizontal-light@Nx.png` | 가로형 로고, 밝은 배경용 (PRINT = 남색) |
| `svg/*.svg` | 위와 같은 그림의 SVG (도트 1칸 = 1 단위) |

## 사용 규칙

- 확대는 항상 **정수 배율 + 최근접 이웃(nearest-neighbor)**. 부드럽게 보간하면 도트가 뭉개집니다.
- CSS로 표시할 때: `image-rendering: pixelated;`
- SVG에는 `shape-rendering="crispEdges"`가 들어 있습니다.
- 16px 이하에서는 반드시 `icon-16` 도안을 씁니다 (24px 도안을 축소하지 않음).

## 색

| 이름 | 값 | 쓰임 |
|---|---|---|
| 호박 (강조색) | `#EF9F27` | 고양이 털, PAW 글자 |
| 호박 그림자 | `#BA7517` | 이마 줄무늬, 얼굴 음영 |
| 외곽선 | `#412402` | 고양이 외곽선 (= 강조색 위 글자색) |
| 크림 | `#FAEEDA` | 앞발, 주둥이, 종이 윗변 |
| 청사진 | `#185FA5` | 두루마리 종이 |
| 청사진 격자 | `#378ADD` | 종이 격자, 두루마리 말린 부분 |
| 남색 | `#0C447C` | 두루마리 끝, 밝은 배경용 PRINT 글자 |
| 아이콘 배경 | `#071B30` | 앱 아이콘 타일 배경 |

## 라이선스

이 저장소의 다른 코드와 같이 PolyForm Noncommercial 1.0.0 라이선스입니다. 독자적으로 그린 그림이며 Mojang 에셋을 포함하지 않습니다.
