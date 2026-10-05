# CLI installation / Установка CLI

## English

Install Java 17 or newer first. The installers download the current CLI archive, verify its SHA-256
checksum, check that the CLI starts and add its `bin` directory to your user `PATH`.
They run without administrator privileges and do not install Java or set database credentials.
The same archives work on x64 and ARM64 with a matching Java installation.

**Windows — PowerShell 5.1 or 7:**

```powershell
& ([scriptblock]::Create((irm https://raw.githubusercontent.com/thirtyeighttwentysix/volan/main/scripts/install.ps1)))
```

**Linux / macOS — Bash:**

```bash
curl -fsSL https://raw.githubusercontent.com/thirtyeighttwentysix/volan/main/scripts/install.sh | bash
```

Linux/macOS require `curl`, `tar` and `sha256sum` or `shasum` (macOS includes `shasum`).
The default destination is `%LOCALAPPDATA%\Programs\Volan` on Windows and
`~/.local/share/volan` on Linux/macOS. Repeating the command upgrades the installation.
The previous version is retained until download verification and the launch check succeed.
Unrelated directories are never replaced. Concurrent installation into the same directory is refused.

Run `volan --help` after installation. Windows updates the current PowerShell session and the user
`PATH`; restart other terminals. On Linux/macOS open a new terminal, or run
`source "$HOME/.config/volan/env.sh"` in Bash/Zsh or
`source "$HOME/.config/volan/env.fish"` in Fish. An installer subprocess cannot change its parent
terminal's environment. Bash, Zsh and Fish startup settings are configured without duplicating
the installer entry. Other shells require manual `PATH` configuration.

### Pin a release or choose an installation directory

`scripts/cli-release.txt` points to the current published CLI release, including previews.
Use `VOLAN_TAG` to select a specific GitHub release containing CLI assets; older alpha.1/alpha.2
library releases have no CLI assets. `JAVA_HOME`, when set, takes precedence over Java on `PATH`.

```powershell
& ([scriptblock]::Create((irm https://raw.githubusercontent.com/thirtyeighttwentysix/volan/main/scripts/install.ps1))) -Tag v1.0.0 -InstallDir "$env:LOCALAPPDATA\Programs\Volan"
```

```bash
curl -fsSL https://raw.githubusercontent.com/thirtyeighttwentysix/volan/main/scripts/install.sh |
  VOLAN_TAG=v1.0.0 VOLAN_INSTALL_DIR="$HOME/.local/share/volan" bash
```

Both installers also accept `VOLAN_INSTALL_DIR`. Keep the same directory when upgrading.
For a manual/offline install, download `volan-cli.zip` (Windows) or `volan-cli.tar.gz` (Linux/macOS)
and `SHA256SUMS` from the [CLI release](https://github.com/thirtyeighttwentysix/volan/releases/tag/v1.0.0),
verify the archive checksum, extract it and add the extracted `volan/bin` directory to `PATH`.
The same release publishes matching 1.0.0 libraries and build plugins to Maven Central.

### Remove the CLI

Delete the dedicated installation directory. On Windows remove its `bin` entry from your user `PATH`
in Environment Variables. On Linux/macOS delete `~/.config/volan/env.sh` and `env.fish`, and remove
the `# Volan CLI` entry and its following line from the shell startup files configured by the installer.
Restart the terminal. Project schemas and database credentials live separately.

## Русский

Сначала установи Java 17 или новее. Выполни команду для своей системы выше: установщик скачает
актуальный CLI, проверит SHA-256, проверит запуск и добавит `volan` в пользовательский `PATH`.
Права администратора не нужны. Java и переменные подключения к базе нужно настроить отдельно.
Архивы подходят для x64 и ARM64 при наличии Java для соответствующей архитектуры.

Windows: установка в `%LOCALAPPDATA%\Programs\Volan`, команда доступна сразу в текущем PowerShell;
другие терминалы нужно перезапустить. Linux/macOS: установка в `~/.local/share/volan`, настройка
Bash, Zsh и Fish; открой новый терминал или выполни указанную выше команду `source`.
Для других оболочек добавь папку `bin` в `PATH` вручную.

Повторный запуск той же команды обновляет CLI. Установщик сначала проверяет скачанный архив и запуск,
затем заменяет установленную версию. Посторонние каталоги не перезаписываются. Для фиксированной
версии используй `VOLAN_TAG` или параметр `-Tag` в PowerShell; для другого каталога —
`VOLAN_INSTALL_DIR` или `-InstallDir`. При обновлении используй прежний каталог.
Если задан `JAVA_HOME`, Java будет выбрана из него.

Проверь установку командой `volan --help`. Установщик ставит CLI 1.0.0; библиотеки и плагины
той же версии доступны в Maven Central. Старые релизы alpha.1/alpha.2 не содержат архивов CLI.
`volan init` создаёт схему, настройка Gradle/Maven описана в [build-plugins.md](build-plugins.md).

Для удаления удали каталог установки и его запись в пользовательском `PATH` на Windows.
На Linux/macOS также удали файлы `~/.config/volan/env.sh`, `env.fish` и добавленные блоки
`# Volan CLI` со следующей строкой из файлов настройки оболочки. Перезапусти терминал.
