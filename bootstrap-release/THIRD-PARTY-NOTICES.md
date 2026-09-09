# Third-party notices

Эта сборка содержит нативную среду выполнения и компоненты совместимости, но не
содержит оригинальные игровые файлы Minecraft Galaxy.

## Azul Zulu OpenJDK 21

OpenJDK распространяется по GNU General Public License version 2 с исключением
Classpath. Полные тексты лицензий и уведомлений находятся внутри приложения в
`Contents/Resources/java21-arm64/Home/legal` и `Home/DISCLAIMER`.

## LWJGL 3.4.3

Copyright (c) 2012-present Lightweight Java Game Library. BSD 3-Clause.
Core, OpenGL, OpenAL and GLFW Java modules are combined for the client's fixed
classpath; core/OpenGL natives are the official macOS ARM64 builds.
The original multi-release classes and version metadata are retained.
License: `Contents/Resources/Licenses/LWJGL3-LICENSE.txt`.

## GLFW 3.5.1

Copyright (c) 2002-2006 Marcus Geelnard; Copyright (c) 2006-2019 Camilla Löwy.
zlib/libpng license. Built locally for ARM64/macOS 14 from upstream commit
`d9d6f0f1f967807ffade6598ea9a631ebaf37a56`, with a narrow NSGL update lock patch
to serialize Cocoa resize against the separate render owner, and scoped AppKit
fullscreen-failure notifications. The original GLFW delegate is retained.
Provenance: `Contents/Resources/Licenses/GLFW-BUILD.md`.
License: `Contents/Resources/Licenses/GLFW-LICENSE.txt`.

## LWJGL 2 — platform-neutral compatibility API

Copyright © LWJGL contributors. BSD license.
Legacy Keyboard/Mouse/Cursor event handling, value classes and utilities remain.
The native Cocoa window/input backend and `libmcgl-window.dylib` are replaced
by GLFW and a small main-thread dispatch bridge.
License: `Contents/Resources/Licenses/LWJGL2-COMPAT-LICENSE.txt`.

## JInput

Copyright © JInput contributors. BSD-style license.

## OpenAL Soft

Copyright © OpenAL Soft contributors. GNU LGPL version 2 or later.

## ASM

Copyright © OW2 Consortium. BSD 3-Clause license. Библиотека используется только
локальными установочными инструментами для применения оконных/графических
патчей и адаптации вызовов LWJGL к официально загруженному клиенту.

## Значки профессий MCGL

Семь значков из https://forum.minecraft-galaxy.ru/wiki/42 включены в интерфейс
менеджера аккаунтов. Project Galaxy / авторы изображений сохраняют права;
MIT-лицензия нашего кода на эти изображения не распространяется.
Точные источники: Contents/Resources/Professions/README.md.

Minecraft Galaxy, Minecraft, остальные игровые ресурсы, названия и товарные
знаки принадлежат соответствующим правообладателям. Оригинальные игровые
JAR, текстуры мира, музыка и аккаунты игроков в этот архив не включены.
