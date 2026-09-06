# Оформление 1.6.6 — «Новая орбита»

## Актуальная иконка: нативная белая подложка

После выпуска, 6 сентября 2026 года, добавлена белая скруглённая подложка в
стиле macOS. Её рисует `tools/ComposeAppIcon.swift` через AppKit; исходный куб
и орбита сохранены в `native-launcher/Assets/app-icon-symbol.png` без перерисовки.
Результат — `native-launcher/Assets/app-icon.png` и `docs/branding/project-icon-macos.png`
(1024 × 1024), форумная версия — `docs/forum-icon-1.6.6-macos.png` (220 × 220).
Рисование выполняется только при сборке. Релиз 1.6.6 перепакован с новой иконкой;
версия и игровая логика не изменились. История прежних вариантов ниже сохранена.
Параметры и файлы: [иконки](branding/README.md).

## Финальная последовательность правок

Каждый следующий промпт применялся к результату предыдущего шага соответствующего изображения.

### Финальный фон — затемнение и сине-фиолетовая палитра

```text
Use case: lighting-weather. Asset type: final static launcher background. Input image 1 is the EDIT TARGET. Keep its exact composition, voxel planet shape, orbit placement, landscape framing, left two-thirds empty for text, and all geometry unchanged. Change only the color and lighting: much darker midnight blue and deep violet cosmic palette, nearly black navy #070713 left side, shadowy indigo #15132D, muted purple nebula #403068 and subtle sapphire accents. Lower the brightness of the bright icy planet and bloom substantially; use restrained soft periwinkle-violet edge lighting instead of electric cyan. Moody, calm, premium, readable under a dark interface. No magenta or pink saturation, no bright white glow, no green or brown. Preserve full-bleed image, no text, no UI, no new objects, no border.
```

### Иконка — изменение палитры

```text
Use case: lighting-weather. Asset type: final transparent macOS app icon. Input image 1 is the EDIT TARGET. Preserve exactly the single large voxel cube and its orbit, rounded-square app body, bold silhouette, proportions and clean geometry. Change ONLY the palette and light: significantly darker midnight navy rounded-square body, deep indigo and violet cube faces, controlled sapphire top-face reflections, restrained soft periwinkle-lavender orbit with a hint of blue. Remove dazzling white/cyan hotspots and reduce bloom for a calm premium dark blue-purple icon, still legible at small Dock size. No neon pink or magenta, no green, no new details or text. Preserve genuinely transparent RGBA outside the rounded-square body and in all four outer corners; do not flatten alpha to black, white, or checkerboard.
```

### Финальная иконка приложения — прозрачный куб и орбита

```text
Use case: background-extraction. Asset type: final transparent macOS application icon. Input image 1 is the EDIT TARGET. Remove the entire rounded-square dark background plate, its border, any shadow from that plate, and the checkerboard backdrop. Keep ONLY the central large blue-violet isometric voxel cube and the single lavender-blue elliptical orbital ring wrapping around it. Preserve their exact shape, viewpoint, relative proportions, internal square details, dark indigo/violet colors, subtle highlights and beautiful front/behind ring occlusion. The cube-and-orbit symbol should occupy about 88 percent of the square canvas, centered, with comfortable transparent margins and no clipping. Output TRUE transparent RGBA pixels everywhere outside the cube and orbit silhouette, including the empty spaces inside the orbital ring. No replacement background of any color, no rounded square, no border or app-tile body, no drawn checkerboard, no text or new objects. Preserve crisp antialiased silhouette and minimal existing ring glow only immediately along its edge.
```

### Финальная иконка GitHub/форума — тёмная подложка

```text
Use case: precise-object-edit. Asset type: final square GitHub/forum project avatar WITH a dark background. Input image 1 is the EDIT TARGET. Correct only its backdrop: replace ALL visible white and light-gray checkerboard areas surrounding the dark rounded-square tile with one uniform opaque midnight-navy color #070713, seamlessly matching the dark tile. The output must be a completely opaque square image with a calm dark background extending all the way to all four canvas edges. Keep the exact blue-violet cube and lavender orbital ring unchanged, their shape, colors, lighting, large scale, pose and position unchanged. No checkerboard anywhere, no light corners, no new elements, no text. This version is expressly intended WITH a dark background, not a transparent cutout.
```

## Концепция

Статический тёмный сине-фиолетовый космос, крупные геометрические формы,
куб с орбитой в иконке приложения (после обновления — на белой скруглённой подложке).
Нативный AppKit-интерфейс: боковая навигация, отдельная форма входа,
карточки памяти и производительности, журнал с внутренними отступами,
единая кнопка запуска/остановки.
Дублирующий заголовок текущей вкладки убран; подписи галочек явно светлые.
Никаких анимированных фонов, веб-движков и дополнительных кадровых таймеров.

## Ресурсы

После публикации исправлена отдельная картинка для форума и GitHub: тёмная
скруглённая плашка сохранена, пространство снаружи неё сделано прозрачным.
Файлы той правки: `docs/branding/project-icon-rounded.png` и
`docs/forum-icon-1.6.6-rounded.png`. На том этапе иконка внутри приложения и DMG не менялись.
Подробности правки: [иконки для публикаций](branding/README.md).

- `native-launcher/Assets/launcher-background.png` — фон, 1536 × 1024.
- `native-launcher/Assets/app-icon-symbol.png` — исходный символ с альфа-каналом, 1254 × 1254.
- `native-launcher/Assets/app-icon.png` — актуальная композиция с белой подложкой, 1024 × 1024.
- `docs/branding/project-icon.png` — отдельная иконка с тёмным фоном для GitHub, 1254 × 1254.
- `docs/forum-icon.png` — уменьшенная версия иконки с фоном для форума, 220 × 220.
- `docs/screenshots/1.6.6/` — реальные снимки окна собранного приложения, не макеты.

Фон и иконки созданы встроенным инструментом ImageGen в Codex: сначала
генерация новых растровых изображений, затем редактирование созданных вариантов
по пожеланиям автора (более тёмная палитра, отдельный прозрачный значок).
Это оформление неофициального
порта, не официальные изображения MCGL. Промпты ниже сохранены полностью;
повторная генерация не гарантирует побайтово одинаковый результат.

## Первоначальный промпт фона

```text
Use case: stylized-concept. Asset type: production raster background for a macOS Minecraft Galaxy ARM64 launcher, landscape approximately 3:2. Primary request: unique deep blue cosmic voxel universe. Scene: a dark midnight-navy starfield with a beautifully lit small fractured voxel planet, made of clean basalt-blue block terraces and luminous ice-blue crystalline surfaces, floating in space, encircled by one delicate cyan orbital arc. Restrained sapphire nebula behind the planet, a few tiny stars; cinematic depth, elegant and sophisticated, crisp large geometric forms. Composition for real UI: main planet and ring in the RIGHTMOST quarter of the image centered at x=82%, y=48%; LEFT TWO THIRDS nearly empty very dark navy with extremely subtle atmospheric texture, reserved for large readable text and a sign-in panel. Full-bleed artwork, no frames or panels. Color palette: midnight #060D20, deep ultramarine #14284F, cobalt #306BFF, icy cyan #70DBFF, cool white highlights. No green, brown, orange or pink. Avoid busy noise, tiny terrain clutter, characters, spaceships, text, letters, logos, watermarks, UI controls. Static premium game-launcher key art, tasteful light bloom only at the right, not across text area.
```

## Первоначальный промпт иконки

```text
Use case: logo-brand. Asset type: final macOS app icon for Minecraft Galaxy ARM64, 1024 by 1024 square. Primary request: a unique minimalist blue-cosmic icon. Single large isometric cobalt-blue voxel cube with ONLY a few big square insets on its three faces, crisp icy cyan top face and deep ultramarine side face. One elegant luminous cyan elliptical orbital ring wraps around the cube, passing behind top-right and in front lower-left. Cube plus orbit occupy 80 percent of the icon body, a bold legible silhouette at small Dock sizes. The body is a midnight-navy macOS rounded square with subtle blue radial illumination, restrained material depth and soft specular edges. The entire rounded square is inset by 6% inside the image, with truly transparent RGBA pixels outside its silhouette and all four corners. No drawn checkerboard, no white or black corner background, no starfield, no tiny particles or excessive texture, no text, letters, numbers, logos, green, brown, magenta or orange. Balanced, iconic, premium, calm. Keep the orbit comfortably inside the rounded square, not clipped.
```
