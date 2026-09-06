# Иконки приложения, форума и GitHub

## Текущий вариант: белая подложка средствами macOS

С 6 сентября 2026 года используется белый скруглённый квадрат с прозрачными
внешними углами. Исходный куб с орбитой сохранён без изменений в
`native-launcher/Assets/app-icon-symbol.png`. `tools/ComposeAppIcon.swift`
рисует подложку через AppKit (`NSBezierPath`) и накладывает символ поверх неё.
Отступ подложки — 8% полотна, радиус — 18.5%, отступ слоя символа — 12%.
Внешней тени нет; все пиксели за краем плашки прозрачны.
Это фиксированный рисунок на этапе сборки, не системный Liquid Glass и не
фоновая работа лаунчера. На FPS и запуск игры композиция не влияет.

**Разные назначения:** белая `app.icns` служит системной иконкой в Dock и
Finder. В боковом меню лаунчера загружается отдельный `app-icon-symbol.png`
с прозрачным фоном. Сборка включает оба файла; UI-тест проверяет, что в
интерфейсе используется именно прозрачный символ, а не белая плашка.

- `project-icon-macos.png` — 1024 × 1024, для GitHub.
- `../forum-icon-1.6.6-macos.png` — 220 × 220, для форума.
- `../../native-launcher/Assets/app-icon.png` — такое же полноразмерное превью.

[Прямой URL для форума](https://raw.githubusercontent.com/prilepv/mcgl-arm64/main/docs/forum-icon-1.6.6-macos.png).
Старая прозрачная иконка приложения и тёмные варианты сохранены, тег `v1.6.6`
не перемещён. DMG того же релиза перепакован с новой иконкой; её использование
не требует изменения Java или игрового рендерера.

Варианты белой подложки сначала пробовались встроенным ImageGen в режиме
редактирования, но не вошли в приложение или публикацию: по просьбе автора
итоговая подложка создаётся нативно, без генерации изображения и маски.
История генерации самого символа: [DESIGN-1.6.6.md](../DESIGN-1.6.6.md).

## Предыдущий вариант: тёмная плашка с прозрачными углами

Исправление после публикации 1.6.6. Тёмная скруглённая плашка с кубом и орбитой
сохранена; убран только фон за её пределами. PNG содержит настоящий альфа-канал,
а не нарисованную шахматку.

### Файлы предыдущего варианта

- `project-icon-rounded.png` — 1254 × 1254, для страницы GitHub.
- `../forum-icon-1.6.6-rounded.png` — 220 × 220, для форума.

Старые `project-icon.png` и `../forum-icon.png` сохранены для истории.
Новый адрес не зависит от кеша старой картинки:
[PNG для форума](https://raw.githubusercontent.com/prilepv/mcgl-arm64/main/docs/forum-icon-1.6.6-rounded.png).
Тег, DMG и иконка внутри приложения не изменялись.

### Проверка предыдущего варианта

Проверены альфа-канал, четыре внешних угла и прозрачность всей внешней границы
у полного и уменьшенного PNG. Допуск на границе — менее 0.5% средней непрозрачности;
в углах — один уровень 8-битного альфа-канала. Точки внутри тёмной плашки
проверены отдельно: плашка остаётся непрозрачной.
У полноразмерного изображения около 28.2% выборки пикселей прозрачно.

### Редактирование предыдущего варианта

Использован встроенный ImageGen в режиме редактирования, не CLI/API.
Первый результат с нарисованной шахматкой отклонён после проверки отсутствия
альфа-канала. Принят результат второго прохода.

Первый запрос к исходному `project-icon.png`:

```text
Use case: background-extraction.
Asset type: corrected transparent PNG forum and GitHub icon.
Input image 1: EDIT TARGET.
Primary request: remove ONLY the dark square canvas background OUTSIDE the existing rounded-square navy tile. Keep the entire rounded-square tile itself, its luminous violet-blue rim, dark interior, cube, orbit, lighting, proportions, scale and position exactly unchanged. The rounded-square tile is the object being cut out; do NOT cut out the cube alone.
Make every pixel outside the tile silhouette genuinely transparent (RGBA alpha 0), including all four canvas corners and the narrow margins along all four sides. Keep every pixel inside the rounded tile opaque, including the dark regions between cube and rim. Preserve a clean antialiased curved outer edge. Do not draw a checkerboard, do not replace the outer backdrop with white or black, do not add drop shadow outside the tile, no new objects, no recoloring, no text, no redesign. Same square framing and comfortable transparent margins as input. Output a PNG with a real alpha channel.
```

Финальный запрос к результату первого прохода:

```text
Remove the checkerboard background from this image and return the rounded-square icon as a transparent PNG cutout. The entire dark rounded-square plate with the cube and orbit must remain intact and opaque. Everything outside its rounded outline must have actual alpha transparency. Do not generate or draw any background or transparency checker pattern. Keep the icon itself unchanged.
```

Уменьшенная версия получена из проверенного PNG стандартным масштабированием
с сохранением альфа-канала.
