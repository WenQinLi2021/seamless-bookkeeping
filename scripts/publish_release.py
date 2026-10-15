#!/usr/bin/env python3
"""构建 debug APK 并发布到 GitHub Release，供手机端「设置 → 应用更新」直接安装。

为什么需要它：应用内更新要有一个"能在手机上访问到的、带权限控制的下载地址"。
GitHub Release 正好满足——仓库是私有的，APK 放上去只有带 token 的人能拿。

用法
----
    # 先在 GitHub 上签发一个有该仓库 Contents/Releases 写权限的 token
    export GITHUB_TOKEN=ghp_xxx          # 或者交给下面的 token 文件
    python scripts/publish_release.py --notes "修复了 xx"

约定（客户端 GitHubUpdateSource 依赖这两行，不要删）
    Release 正文首行必须是 versionCode=<整数>
    正文里带上 sha256=<64 位十六进制>

令牌查找顺序：--token 参数 > GITHUB_TOKEN 环境变量 > ~/.seamless-release-token
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import pathlib
import subprocess
import sys
import urllib.error
import urllib.request

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")

ROOT = pathlib.Path(__file__).resolve().parent.parent
DEFAULT_OWNER = "WenQinLi2021"
DEFAULT_REPO = "seamless-bookkeeping"
API = "https://api.github.com"
UPLOAD_API = "https://uploads.github.com"
TOKEN_FILE = pathlib.Path.home() / ".seamless-release-token"
APK_MIME = "application/vnd.android.package-archive"


def request(method, url, token, data=None, content_type="application/json", allow_404=False):
    headers = {
        "Authorization": "Bearer " + token,
        "Accept": "application/vnd.github+json",
        "X-GitHub-Api-Version": "2022-11-28",
        "User-Agent": "seamless-bookkeeping-release",
    }
    if data is not None:
        headers["Content-Type"] = content_type
    req = urllib.request.Request(url, data=data, method=method, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=600) as resp:
            body = resp.read()
            return json.loads(body.decode("utf-8")) if body else {}
    except urllib.error.HTTPError as exc:
        if allow_404 and exc.code == 404:
            return None
        detail = exc.read().decode("utf-8", "replace")[:400]
        raise SystemExit("GitHub API 调用失败：HTTP %d\n%s" % (exc.code, detail))


def build() -> None:
    gradlew = ROOT / ("gradlew.bat" if os.name == "nt" else "gradlew")
    if not gradlew.exists():
        raise SystemExit("找不到 gradlew，脚本必须放在项目根目录的 scripts/ 下")
    cmd = ["cmd", "/c", str(gradlew), "assembleDebug"] if os.name == "nt" else ["./gradlew", "assembleDebug"]
    print("==> 构建 debug APK …")
    result = subprocess.run(cmd, cwd=str(ROOT))
    if result.returncode != 0:
        raise SystemExit("构建失败，已中止发布")


def locate_apk():
    metadata = ROOT / "app" / "build" / "outputs" / "apk" / "debug" / "output-metadata.json"
    if not metadata.exists():
        raise SystemExit("找不到 %s，请先构建（或去掉 --skip-build）" % metadata)
    data = json.loads(metadata.read_text(encoding="utf-8"))
    element = data["elements"][0]
    apk = metadata.parent / element["outputFile"]
    if not apk.exists():
        raise SystemExit("找不到 APK：%s" % apk)
    return apk, int(element["versionCode"]), str(element["versionName"])


def sha256_of(path: pathlib.Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def default_notes() -> str:
    try:
        subject = subprocess.run(
            ["git", "log", "-1", "--pretty=%s"],
            cwd=str(ROOT), capture_output=True, text=True, check=True,
        ).stdout.strip()
        return subject or "无更新说明"
    except Exception:
        return "无更新说明"


def main() -> None:
    parser = argparse.ArgumentParser(description="构建并发布 APK 到 GitHub Release")
    parser.add_argument("--owner", default=DEFAULT_OWNER)
    parser.add_argument("--repo", default=DEFAULT_REPO)
    parser.add_argument("--token", default=None, help="访问令牌；不传则读环境变量或 ~/.seamless-release-token")
    parser.add_argument("--notes", default=None, help="更新说明，手机端会显示这段文字")
    parser.add_argument("--skip-build", action="store_true", help="跳过构建，直接发布已有的 APK")
    parser.add_argument("--force", action="store_true", help="已存在同名 tag 的 Release 时先删除再重建")
    args = parser.parse_args()

    token = (
        args.token
        or os.environ.get("GITHUB_TOKEN")
        or (TOKEN_FILE.read_text(encoding="utf-8").strip() if TOKEN_FILE.exists() else "")
    )
    if not token:
        raise SystemExit(
            "缺少访问令牌。\n"
            "  方式一：export GITHUB_TOKEN=<你的 token>\n"
            "  方式二：把 token 写进 %s" % TOKEN_FILE
        )

    if not args.skip_build:
        build()

    apk, version_code, version_name = locate_apk()
    digest = sha256_of(apk)
    tag = "v" + version_name
    notes = args.notes or default_notes()

    print("==> 版本：%s (versionCode=%d)" % (version_name, version_code))
    print("==> 安装包：%s（%.1f MB）" % (apk.name, apk.stat().st_size / 1024 / 1024))
    print("==> SHA-256：%s" % digest)

    base = "%s/repos/%s/%s" % (API, args.owner, args.repo)

    existing = request("GET", "%s/releases/tags/%s" % (base, tag), token, allow_404=True)
    if existing is not None:
        if not args.force:
            raise SystemExit(
                "Release %s 已存在。加 --force 可以删除后重新发布（手机上会更不出新版本）。" % tag
            )
        print("==> 删除已存在的 Release %s" % tag)
        request("DELETE", "%s/releases/%d" % (base, existing["id"]), token)
        request("DELETE", "%s/git/refs/tags/%s" % (base, tag), token, allow_404=True)

    # 正文前两行是机器可读的约定字段，客户端靠它们比较版本和校验完整性
    body = "versionCode=%d\nsha256=%s\n\n%s" % (version_code, digest, notes)

    print("==> 创建 Release %s" % tag)
    release = request(
        "POST",
        "%s/releases" % base,
        token,
        data=json.dumps(
            {"tag_name": tag, "name": tag, "body": body, "draft": False, "prerelease": False}
        ).encode("utf-8"),
    )

    payload = apk.read_bytes()
    print("==> 上传安装包（%.1f MB）…" % (len(payload) / 1024 / 1024))
    asset = request(
        "POST",
        "%s/repos/%s/%s/releases/%d/assets?name=%s" % (UPLOAD_API, args.owner, args.repo, release["id"], apk.name),
        token,
        data=payload,
        content_type=APK_MIME,
    )

    print()
    print("发布完成：%s" % release["html_url"])
    print("安装包：%s（%d 字节）" % (asset["name"], asset["size"]))
    print()
    print("手机端：设置 → 应用更新 → 检查更新")


if __name__ == "__main__":
    main()
