//! jscpd for Zed: starts `jscpd --lsp` for the worktree with a binary from
//! the `lsp.jscpd.binary` setting, from the PATH, or downloaded from the
//! GitHub release and checked against the release's `checksums.txt`.

use std::fs;
use std::io::Read;

use sha2::{Digest, Sha256};
use zed_extension_api::{
    self as zed,
    http_client::{HttpMethod, HttpRequest, RedirectPolicy},
    process::Command as Process,
    serde_json::Value,
    settings::LspSettings,
    Architecture, DownloadedFileType, LanguageServerId, LanguageServerInstallationStatus, Os,
    Result,
};

const REPO: &str = "kucherenko/jscpd";
const SERVER_ID: &str = "jscpd";
/// The first release with `--lsp`.
const MIN_VERSION: (u64, u64, u64) = (5, 4, 0);

struct JscpdExtension {
    /// The binary found or downloaded last time, so the next worktree does
    /// not look again.
    cached: Option<String>,
}

impl zed::Extension for JscpdExtension {
    fn new() -> Self {
        Self { cached: None }
    }

    fn language_server_command(
        &mut self,
        id: &LanguageServerId,
        worktree: &zed::Worktree,
    ) -> Result<zed::Command> {
        let settings = LspSettings::for_worktree(SERVER_ID, worktree).unwrap_or_default();
        let binary = settings.binary.as_ref();
        let mut args = binary.and_then(|b| b.arguments.clone()).unwrap_or_default();
        if !args.iter().any(|a| a == "--lsp") {
            args.insert(0, "--lsp".to_string());
        }
        let env = binary
            .and_then(|b| b.env.clone())
            .map(|vars| vars.into_iter().collect())
            .unwrap_or_else(|| worktree.shell_env());
        let command = self.find_binary(id, worktree, binary.and_then(|b| b.path.clone()))?;
        Ok(zed::Command { command, args, env })
    }

    /// `lsp.jscpd.initialization_options` holds the keys of `.jscpd.json`,
    /// with the `lsp` section for the analyses; `settings` is taken as the
    /// same thing when that is what the user wrote.
    fn language_server_initialization_options(
        &mut self,
        _id: &LanguageServerId,
        worktree: &zed::Worktree,
    ) -> Result<Option<Value>> {
        let settings = LspSettings::for_worktree(SERVER_ID, worktree).unwrap_or_default();
        Ok(settings.initialization_options.or(settings.settings))
    }

    fn language_server_workspace_configuration(
        &mut self,
        _id: &LanguageServerId,
        worktree: &zed::Worktree,
    ) -> Result<Option<Value>> {
        let settings = LspSettings::for_worktree(SERVER_ID, worktree).unwrap_or_default();
        Ok(settings.settings.or(settings.initialization_options))
    }
}

impl JscpdExtension {
    /// The setting wins as it is. A `jscpd` on the PATH is used when it is
    /// new enough for `--lsp`; otherwise a release build is downloaded once.
    fn find_binary(
        &mut self,
        id: &LanguageServerId,
        worktree: &zed::Worktree,
        configured: Option<String>,
    ) -> Result<String> {
        if let Some(path) = configured {
            return Ok(path);
        }
        if let Some(path) = worktree.which("jscpd") {
            match version_of(&path) {
                Some(version) if version >= MIN_VERSION => return Ok(path),
                Some((major, minor, patch)) => eprintln!(
                    "jscpd {major}.{minor}.{patch} at {path} is older than 5.4.0, which added --lsp; using a release build instead"
                ),
                None => eprintln!("{path} does not answer `jscpd --version`; using a release build instead"),
            }
        }
        if let Some(path) = &self.cached {
            if fs::metadata(path).is_ok() {
                return Ok(path.clone());
            }
        }
        let path = self.download(id)?;
        self.cached = Some(path.clone());
        Ok(path)
    }

    /// The latest release build, fetched through the `releases/latest/download`
    /// links rather than the GitHub API, which is rate-limited for anonymous
    /// callers. The version comes from the binary itself.
    fn download(&self, id: &LanguageServerId) -> Result<String> {
        zed::set_language_server_installation_status(
            id,
            &LanguageServerInstallationStatus::Downloading,
        );
        let (os, arch) = zed::current_platform();
        let platform = platform_id(os, arch)?;
        let asset_name = format!("jscpd-{platform}.tar.gz");
        let exe = if os == Os::Windows { "jscpd.exe" } else { "jscpd" };
        let staging = format!("jscpd-download-{platform}");
        let _ = fs::remove_dir_all(&staging);
        let result = fetch_and_unpack(&asset_name, &staging, exe).and_then(|_| {
            let staged = format!("{staging}/{exe}");
            let (major, minor, patch) = version_of(&absolute(&staged))
                .ok_or_else(|| format!("the downloaded {exe} does not answer --version"))?;
            let dir = format!("jscpd-{major}.{minor}.{patch}");
            let _ = fs::remove_dir_all(&dir);
            fs::rename(&staging, &dir).map_err(|e| format!("cannot move {staging} to {dir}: {e}"))?;
            remove_other_versions(&dir);
            Ok(format!("{dir}/{exe}"))
        });
        match result {
            Ok(binary) => {
                zed::set_language_server_installation_status(id, &LanguageServerInstallationStatus::None);
                Ok(absolute(&binary))
            }
            Err(error) => {
                let _ = fs::remove_dir_all(&staging);
                zed::set_language_server_installation_status(
                    id,
                    &LanguageServerInstallationStatus::Failed(error.clone()),
                );
                Err(format!("{error}; install jscpd 5.4.0 or newer yourself and set lsp.jscpd.binary.path if this keeps failing"))
            }
        }
    }
}

/// Downloads the archive, checks its SHA-256 against `checksums.txt`, and
/// writes the executable it holds into `dir`.
fn fetch_and_unpack(asset_name: &str, dir: &str, exe: &str) -> Result<()> {
    let base = format!("https://github.com/{REPO}/releases/latest/download");
    fs::create_dir_all(dir).map_err(|e| format!("cannot create {dir}: {e}"))?;
    let archive = format!("{dir}/{asset_name}");
    zed::download_file(&format!("{base}/{asset_name}"), &archive, DownloadedFileType::Uncompressed)
        .map_err(|e| format!("download of {asset_name} failed: {e}"))?;
    let bytes = fs::read(&archive).map_err(|e| format!("cannot read {archive}: {e}"))?;

    let text = String::from_utf8_lossy(&fetch_bytes(&format!("{base}/checksums.txt"))?).into_owned();
    let expected = text
        .lines()
        .find_map(|line| {
            let mut parts = line.split_whitespace();
            let hash = parts.next()?;
            let name = parts.next()?;
            (name == asset_name).then(|| hash.to_ascii_lowercase())
        })
        .ok_or_else(|| format!("checksums.txt has no entry for {asset_name}"))?;
    let actual = hex(&Sha256::digest(&bytes));
    if actual != expected {
        return Err(format!("checksum mismatch for {asset_name}: expected {expected}, got {actual}"));
    }

    let mut tar = Vec::new();
    flate2::read::GzDecoder::new(&bytes[..])
        .read_to_end(&mut tar)
        .map_err(|e| format!("{asset_name} is not a gzip archive: {e}"))?;
    let file = untar_file(&tar, exe).ok_or_else(|| format!("{asset_name} holds no {exe}"))?;
    let binary = format!("{dir}/{exe}");
    fs::write(&binary, file).map_err(|e| format!("cannot write {binary}: {e}"))?;
    zed::make_file_executable(&binary)?;
    let _ = fs::remove_file(&archive);
    Ok(())
}

fn fetch_bytes(url: &str) -> Result<Vec<u8>> {
    let request = HttpRequest::builder()
        .method(HttpMethod::Get)
        .url(url)
        .header("User-Agent", "jscpd-zed")
        .redirect_policy(RedirectPolicy::FollowAll)
        .build()?;
    Ok(request.fetch()?.body)
}

/// The release asset for this machine.
fn platform_id(os: Os, arch: Architecture) -> Result<&'static str> {
    Ok(match (os, arch) {
        (Os::Mac, Architecture::Aarch64) => "darwin-arm64",
        (Os::Mac, Architecture::X8664) => "darwin-x64",
        (Os::Linux, Architecture::X8664) => {
            if is_musl() {
                "linux-x64-musl"
            } else {
                "linux-x64-gnu"
            }
        }
        (Os::Linux, Architecture::Aarch64) => {
            if is_musl() {
                "linux-arm64-musl"
            } else {
                "linux-arm64-gnu"
            }
        }
        (Os::Windows, Architecture::X8664) => "windows-x64-msvc",
        (Os::Windows, Architecture::Aarch64) => "windows-arm64-msvc",
        (os, arch) => return Err(format!("jscpd has no release build for {os:?} {arch:?}; install it another way and set lsp.jscpd.binary.path")),
    })
}

/// On musl systems `ldd --version` names musl; glibc is the default.
fn is_musl() -> bool {
    Process::new("ldd")
        .arg("--version")
        .output()
        .map(|out| {
            let text = String::from_utf8_lossy(&out.stdout).to_string() + &String::from_utf8_lossy(&out.stderr);
            text.to_ascii_lowercase().contains("musl")
        })
        .unwrap_or(false)
}

/// What `<path> --version` reports, as a version triple.
fn version_of(path: &str) -> Option<(u64, u64, u64)> {
    let out = Process::new(path).arg("--version").output().ok()?;
    let text = String::from_utf8_lossy(&out.stdout).to_string();
    let start = text.find(|c: char| c.is_ascii_digit())?;
    let mut parts = text[start..]
        .split(|c: char| !c.is_ascii_digit())
        .take(3)
        .map(|p| p.parse::<u64>().ok());
    Some((parts.next()??, parts.next()??, parts.next()??))
}

/// The regular file called `name` in a tar archive, directories ignored.
fn untar_file(tar: &[u8], name: &str) -> Option<Vec<u8>> {
    let mut offset = 0;
    while offset + 512 <= tar.len() {
        let header = &tar[offset..offset + 512];
        if header.iter().all(|&b| b == 0) {
            break;
        }
        let field = |range: std::ops::Range<usize>| {
            let raw = &header[range];
            let end = raw.iter().position(|&b| b == 0).unwrap_or(raw.len());
            String::from_utf8_lossy(&raw[..end]).trim().to_string()
        };
        let size = usize::from_str_radix(&field(124..136), 8).unwrap_or(0);
        let kind = header[156];
        let entry_name = field(0..100);
        offset += 512;
        if (kind == b'0' || kind == 0) && entry_name.rsplit('/').next() == Some(name) {
            return tar.get(offset..offset + size).map(|d| d.to_vec());
        }
        offset += size.div_ceil(512) * 512;
    }
    None
}

fn hex(bytes: &[u8]) -> String {
    bytes.iter().map(|b| format!("{b:02x}")).collect()
}

/// A path inside the extension's work directory, made absolute for the
/// command Zed spawns.
fn absolute(relative: &str) -> String {
    std::env::current_dir()
        .map(|cwd| cwd.join(relative).to_string_lossy().into_owned())
        .unwrap_or_else(|_| relative.to_string())
}

/// Older downloads next to the current one are dropped.
fn remove_other_versions(keep: &str) {
    let Ok(entries) = fs::read_dir(".") else {
        return;
    };
    for entry in entries.flatten() {
        let name = entry.file_name().to_string_lossy().into_owned();
        if name.starts_with("jscpd-") && name != keep && entry.path().is_dir() {
            let _ = fs::remove_dir_all(entry.path());
        }
    }
}

zed::register_extension!(JscpdExtension);
