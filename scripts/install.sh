#!/usr/bin/env bash
# Linux/macOS: curl, tar and Java 17+. No root privileges or jq required.
set -euo pipefail
die() { printf 'Volan: %s\n' "$*" >&2; exit 1; }
case "$(uname -s)" in Linux|Darwin) ;; *) die 'Use install.ps1 on Windows.' ;; esac
for tool in curl tar mktemp; do command -v "$tool" >/dev/null || die "Missing required command: $tool"; done
if [[ -n "${JAVA_HOME:-}" ]]; then java="$JAVA_HOME/bin/java"; else java=$(command -v java) || die 'Install Java 17+ first.'; fi
[[ -x "$java" ]] || die 'JAVA_HOME does not contain bin/java. Install Java 17+ or fix JAVA_HOME.'
java_version=$("$java" -version 2>&1) || die 'Cannot start Java. Install Java 17+ first.'
major=$(printf '%s\n' "$java_version" | sed -nE 's/.*version "(1\.)?([0-9]+).*/\2/p' | head -n 1)
[[ "$major" =~ ^[0-9]+$ ]] && (( major >= 17 )) || die 'Volan requires Java 17 or newer.'
if command -v sha256sum >/dev/null; then hash() { sha256sum "$1" | cut -d ' ' -f 1; }
elif command -v shasum >/dev/null; then hash() { shasum -a 256 "$1" | cut -d ' ' -f 1; }
else die 'Missing sha256sum or shasum.'; fi
root=${VOLAN_INSTALL_DIR:-"$HOME/.local/share/volan"}
[[ "$root" == /* && "$root" != *:* && "$root" != *$'\n'* && "$root" != *$'\r'* ]] || die 'VOLAN_INSTALL_DIR must be an absolute path without PATH separators or line breaks.'
root=${root%/}
[[ -n "$root" && "$(basename "$root")" != . && "$(basename "$root")" != .. ]] || die 'Choose a dedicated installation directory.'
parent=$(dirname "$root")
mkdir -p "$parent"
parent=$(cd "$parent" && pwd -P)
root="$parent/$(basename "$root")"
[[ "$root" != "$HOME" && "$root" != / ]] || die 'Choose a dedicated installation directory, not your home or filesystem root.'
[[ ! -L "$root" ]] || die 'The installation directory must not be a link.'
if [[ -e "$root" && ! -f "$root/.volan-install" ]]; then
    [[ -f "$root/bin/volan" ]] && compgen -G "$root/lib/volan-cli-*.jar" >/dev/null || die "Refusing to replace an unrelated directory: $root"
fi
lock="$root.install-lock"
mkdir "$lock" || die 'Another installer may be running; inspect the .install-lock directory.'
stage=''; backup=''; activated=0; completed=0
cleanup() {
    status=$?
    if (( ! completed )); then
        if (( activated )); then rm -rf -- "$root"; fi
        if [[ -n "$backup" && -d "$backup/previous" ]]; then mv -- "$backup/previous" "$root"; fi
    fi
    [[ -z "$stage" ]] || rm -rf -- "$stage"
    [[ -z "$backup" ]] || rm -rf -- "$backup"
    rmdir "$lock"
    exit "$status"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
stage=$(mktemp -d "$parent/.volan-stage.XXXXXXXX")
fetch() { curl --fail --silent --show-error --location --proto '=https' --tlsv1.2 --retry 2 --connect-timeout 20 --max-time 180 "$1" -o "$2"; }
tag=${VOLAN_TAG:-}
if [[ -z "$tag" ]]; then
    fetch 'https://raw.githubusercontent.com/thirtyeighttwentysix/volan/main/scripts/cli-release.txt' "$stage/channel.txt"
    tag=$(tr -d '\r\n' < "$stage/channel.txt")
fi
[[ "$tag" =~ ^(cli-)?v[0-9]+\.[0-9]+\.[0-9]+(-[A-Za-z0-9.-]+)?$ ]] || die "Invalid release tag: $tag"
base="https://github.com/thirtyeighttwentysix/volan/releases/download/$tag"
printf 'Downloading Volan CLI (%s)...\n' "$tag"
fetch "$base/volan-cli.tar.gz" "$stage/volan-cli.tar.gz"
fetch "$base/SHA256SUMS" "$stage/SHA256SUMS"
expected=$(awk '$2 == "volan-cli.tar.gz" {print $1}' "$stage/SHA256SUMS")
[[ "$expected" =~ ^[a-f0-9]{64}$ && "$(hash "$stage/volan-cli.tar.gz")" == "$expected" ]] || die 'CLI checksum verification failed.'
tar -tzf "$stage/volan-cli.tar.gz" > "$stage/entries"
while IFS= read -r name; do
    [[ "$name" == volan/* && "$name" != *\\* && ! "$name" =~ (^|/)\.\.?(/|$) ]] || die "Unsafe archive entry: $name"
done < "$stage/entries"
# Only regular files and directories are allowed; reject links, devices and FIFOs.
tar -tvzf "$stage/volan-cli.tar.gz" > "$stage/modes"
awk 'substr($0,1,1) != "-" && substr($0,1,1) != "d" {exit 1}' "$stage/modes" || die 'Unsupported archive entry type.'
tar -xzf "$stage/volan-cli.tar.gz" -C "$stage"
payload="$stage/volan"
[[ -f "$payload/bin/volan" ]] && compgen -G "$payload/lib/volan-cli-*.jar" >/dev/null || die 'Incomplete CLI archive.'
chmod +x "$payload/bin/volan"
"$payload/bin/volan" --help >/dev/null || die 'Downloaded CLI failed its launch check.'
printf '%s\n' "$tag" > "$payload/.volan-install"
if [[ -e "$root" ]]; then
    backup=$(mktemp -d "$parent/.volan-backup.XXXXXXXX")
    mv -- "$root" "$backup/previous"
fi
mv -- "$payload" "$root"
activated=1

# Single-quote paths for shell configuration, including apostrophes and spaces.
quote() { printf "'%s'" "$(printf '%s' "$1" | sed "s/'/'\\\\''/g")"; }
config="$HOME/.config/volan"
mkdir -p "$config"
quoted_bin=$(quote "$root/bin")
printf '# Managed by the Volan CLI installer.\ncase ":$PATH:" in *:%s:*) ;; *) export PATH=%s:"$PATH" ;; esac\n' "$quoted_bin" "$quoted_bin" > "$config/env.sh"
printf '# Managed by the Volan CLI installer.\nfish_add_path --move --path %s\n' "$quoted_bin" > "$config/env.fish"
line='[ ! -f "$HOME/.config/volan/env.sh" ] || . "$HOME/.config/volan/env.sh"'
add_profile() {
    touch "$1"
    if ! grep -Fqx "$line" "$1"; then printf '\n# Volan CLI\n%s\n' "$line" >> "$1"; fi
}
add_profile "$HOME/.profile"
add_profile "$HOME/.bashrc"
for profile in "$HOME/.bash_profile" "$HOME/.bash_login"; do
    [[ ! -f "$profile" ]] || add_profile "$profile"
done
if [[ "${SHELL:-}" == */zsh || -f "${ZDOTDIR:-$HOME}/.zshrc" ]]; then
    mkdir -p "${ZDOTDIR:-$HOME}"
    add_profile "${ZDOTDIR:-$HOME}/.zprofile"
    add_profile "${ZDOTDIR:-$HOME}/.zshrc"
fi
if [[ "${SHELL:-}" == */fish || -d "${XDG_CONFIG_HOME:-$HOME/.config}/fish" ]]; then
    mkdir -p "${XDG_CONFIG_HOME:-$HOME/.config}/fish"
    line='source "$HOME/.config/volan/env.fish"'
    add_profile "${XDG_CONFIG_HOME:-$HOME/.config}/fish/config.fish"
fi
completed=1
printf 'Installed Volan CLI in %s.\n' "$root"
printf 'Open a new terminal, then run: volan --help\n'
if [[ "${SHELL:-}" == */fish ]]; then printf 'For this terminal: source "$HOME/.config/volan/env.fish"\n'
else printf 'For this terminal: source "$HOME/.config/volan/env.sh"\n'; fi
