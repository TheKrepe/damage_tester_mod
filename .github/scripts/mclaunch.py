#!/usr/bin/env python3
"""Minimal Minecraft installer/launcher for CI end-to-end tests (production jars, real loaders).

  mclaunch.py client  --dir D --mc 1.20.1 --loader fabric|neoforge|forge --loader-version V   -> prints the version id
  mclaunch.py launch  --dir D --id ID --java JAVA [--jvm-arg ...] [--game-arg ...]           -> runs the client
  mclaunch.py server  --dir D --mc 1.20.1 --loader ... --loader-version V --java JAVA          -> installs a server, prints the start command

Only what the tests need: offline login, Linux, no sound assets.
"""
import argparse
import concurrent.futures
import hashlib
import json
import os
import shutil
import subprocess
import sys
import urllib.request
import uuid

MANIFEST = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"
UA = {"User-Agent": "damage-tester-ci"}


def log(*a):
    print("[mclaunch]", *a, file=sys.stderr, flush=True)


def fetch(url):
    for attempt in range(4):
        try:
            with urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=120) as r:
                return r.read()
        except Exception as e:  # noqa: BLE001
            if attempt == 3:
                raise RuntimeError(f"download failed: {url}: {e}")


def fetch_json(url):
    return json.loads(fetch(url))


def download(url, path, sha1=None):
    if os.path.exists(path) and os.path.getsize(path) > 0:
        if sha1 is None or hashlib.sha1(open(path, "rb").read()).hexdigest() == sha1:
            return path
    os.makedirs(os.path.dirname(path), exist_ok=True)
    data = fetch(url)
    tmp = path + ".part"
    with open(tmp, "wb") as f:
        f.write(data)
    os.replace(tmp, path)
    return path


def maven_path(name):
    parts = name.split(":")
    group, artifact, version = parts[0], parts[1], parts[2]
    classifier = parts[3] if len(parts) > 3 else None
    ext = "jar"
    if "@" in version:
        version, ext = version.split("@", 1)
    if classifier and "@" in classifier:
        classifier, ext = classifier.split("@", 1)
    file = f"{artifact}-{version}" + (f"-{classifier}" if classifier else "") + f".{ext}"
    return f"{group.replace('.', '/')}/{artifact}/{version}/{file}"


def rules_ok(rules):
    if not rules:
        return True
    allowed = False
    for rule in rules:
        if "features" in rule:
            match = False  # no demo, no custom resolution, no quick play features from the json
        else:
            os_rule = rule.get("os", {})
            match = (not os_rule.get("name") or os_rule.get("name") == "linux") and not os_rule.get("arch")
        if match:
            allowed = rule["action"] == "allow"
    return allowed


# ---------------------------------------------------------------------------------------- vanilla

def vanilla(d, mc):
    vdir = os.path.join(d, "versions", mc)
    vjson = os.path.join(vdir, mc + ".json")
    if not os.path.exists(vjson):
        manifest = fetch_json(MANIFEST)
        entry = next(v for v in manifest["versions"] if v["id"] == mc)
        download(entry["url"], vjson)
    data = json.load(open(vjson))
    download(data["downloads"]["client"]["url"], os.path.join(vdir, mc + ".jar"), data["downloads"]["client"].get("sha1"))
    return data


def assets(d, data):
    idx = data["assetIndex"]
    ipath = os.path.join(d, "assets", "indexes", idx["id"] + ".json")
    download(idx["url"], ipath, idx.get("sha1"))
    objects = json.load(open(ipath))["objects"]
    jobs = []
    for name, obj in objects.items():
        if name.startswith(("minecraft/sounds/", "minecraft/music", "minecraft/resourcepacks")):
            continue  # tests run muted
        h = obj["hash"]
        jobs.append((f"https://resources.download.minecraft.net/{h[:2]}/{h}", os.path.join(d, "assets", "objects", h[:2], h), h))
    with concurrent.futures.ThreadPoolExecutor(16) as ex:
        list(ex.map(lambda j: download(*j), jobs))
    log(f"assets: {len(jobs)} objects")


# ---------------------------------------------------------------------------------------- loaders

def install_client(d, mc, loader, loader_version, java):
    os.makedirs(d, exist_ok=True)
    data = vanilla(d, mc)
    assets(d, data)
    if loader == "fabric":
        vid = f"fabric-loader-{loader_version}-{mc}"
        path = os.path.join(d, "versions", vid, vid + ".json")
        download(f"https://meta.fabricmc.net/v2/versions/loader/{mc}/{loader_version}/profile/json", path)
        return vid
    lp = os.path.join(d, "launcher_profiles.json")
    if not os.path.exists(lp):
        json.dump({"profiles": {}}, open(lp, "w"))
    if loader == "neoforge":
        url = f"https://maven.neoforged.net/releases/net/neoforged/neoforge/{loader_version}/neoforge-{loader_version}-installer.jar"
    else:
        url = f"https://maven.minecraftforge.net/net/minecraftforge/forge/{mc}-{loader_version}/forge-{mc}-{loader_version}-installer.jar"
    inst = download(url, os.path.join(d, "installer.jar"))
    before = set(os.listdir(os.path.join(d, "versions")))
    ok = False
    for flag in ("--installClient", "--install-client"):
        r = subprocess.run([java, "-jar", inst, flag, d], cwd=d, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
        tail = r.stdout[-3000:]
        if r.returncode == 0:
            ok = True
            break
        log(f"installer {flag} failed:\n{tail}")
    if not ok:
        raise SystemExit("loader installer failed")
    created = [v for v in os.listdir(os.path.join(d, "versions")) if v not in before]
    if not created:
        created = [v for v in os.listdir(os.path.join(d, "versions")) if loader in v.lower()]
    vid = sorted(created)[-1]
    return vid


def load_version(d, vid):
    data = json.load(open(os.path.join(d, "versions", vid, vid + ".json")))
    if "inheritsFrom" in data:
        parent = load_version(d, data["inheritsFrom"])
        merged = dict(parent)
        for k, v in data.items():
            if k == "libraries":
                merged["libraries"] = v + parent.get("libraries", [])
            elif k == "arguments":
                args = {"game": list(parent.get("arguments", {}).get("game", [])),
                        "jvm": list(parent.get("arguments", {}).get("jvm", []))}
                args["game"] += v.get("game", [])
                args["jvm"] += v.get("jvm", [])
                merged["arguments"] = args
            else:
                merged[k] = v
        merged["_jar"] = parent.get("_jar", data["inheritsFrom"])
        return merged
    data["_jar"] = vid
    return data


def libraries(d, data):
    cp, seen = [], set()
    jobs = []
    for lib in data.get("libraries", []):
        if not rules_ok(lib.get("rules")):
            continue
        name = lib["name"]
        parts = name.split(":")
        key = ":".join(parts[:2] + parts[3:4])
        if key in seen:
            continue
        seen.add(key)
        art = lib.get("downloads", {}).get("artifact")
        if art:
            path = os.path.join(d, "libraries", art["path"])
            if art.get("url"):
                jobs.append((art["url"], path, art.get("sha1")))
        elif "downloads" in lib and "classifiers" in lib["downloads"]:
            continue  # pre-1.19 natives layout, not needed here
        else:
            rel = maven_path(name)
            path = os.path.join(d, "libraries", rel)
            base = lib.get("url", "https://libraries.minecraft.net/")
            jobs.append((base.rstrip("/") + "/" + rel, path, lib.get("sha1")))
        cp.append(path)
    with concurrent.futures.ThreadPoolExecutor(16) as ex:
        list(ex.map(lambda j: download(*j), jobs))
    return [p for p in cp if os.path.exists(p)]


def substitute(arg, values):
    for k, v in values.items():
        arg = arg.replace("${" + k + "}", v)
    return arg


def flatten(args):
    out = []
    for a in args:
        if isinstance(a, str):
            out.append(a)
        elif rules_ok(a.get("rules")):
            v = a["value"]
            out += v if isinstance(v, list) else [v]
    return out


def launch(d, vid, java, jvm_extra, game_extra, user):
    data = load_version(d, vid)
    cp = libraries(d, data)
    jar_src = os.path.join(d, "versions", data["_jar"], data["_jar"] + ".jar")
    jar = os.path.join(d, "versions", vid, vid + ".jar")
    if not os.path.exists(jar):
        shutil.copy(jar_src, jar)
    cp.append(jar)
    natives = os.path.join(d, "natives")
    os.makedirs(natives, exist_ok=True)
    offline = str(uuid.UUID(bytes=hashlib.md5(("OfflinePlayer:" + user).encode()).digest(), version=3))
    values = {
        "auth_player_name": user, "version_name": vid, "game_directory": d, "assets_root": os.path.join(d, "assets"),
        "game_assets": os.path.join(d, "assets"), "assets_index_name": data["assetIndex"]["id"], "auth_uuid": offline.replace("-", ""),
        "auth_access_token": "0", "auth_session": "0", "clientid": "0", "auth_xuid": "0", "user_type": "legacy",
        "version_type": "release", "natives_directory": natives, "launcher_name": "damage-tester-ci",
        "launcher_version": "1", "classpath": os.pathsep.join(cp), "library_directory": os.path.join(d, "libraries"),
        "classpath_separator": os.pathsep, "user_properties": "{}",
    }
    if "arguments" in data:
        jvm = [substitute(a, values) for a in flatten(data["arguments"].get("jvm", []))]
        game = [substitute(a, values) for a in flatten(data["arguments"].get("game", []))]
    else:
        jvm = [f"-Djava.library.path={natives}", "-cp", values["classpath"]]
        game = [substitute(a, values) for a in data["minecraftArguments"].split()]
    cmd = [java, "-Xmx3G"] + jvm_extra + jvm + [data["mainClass"]] + game + game_extra
    log("launching", vid)
    os.chdir(d)
    os.execv(java, cmd)


def install_server(d, mc, loader, loader_version, java):
    os.makedirs(d, exist_ok=True)
    if loader == "fabric":
        inst = fetch_json("https://meta.fabricmc.net/v2/versions/installer")[0]["version"]
        download(f"https://meta.fabricmc.net/v2/versions/loader/{mc}/{loader_version}/{inst}/server/jar", os.path.join(d, "fabric-server-launch.jar"))
        print(f"{java} @jvm_args.txt -jar fabric-server-launch.jar nogui")
        return
    if loader == "neoforge":
        url = f"https://maven.neoforged.net/releases/net/neoforged/neoforge/{loader_version}/neoforge-{loader_version}-installer.jar"
    else:
        url = f"https://maven.minecraftforge.net/net/minecraftforge/forge/{mc}-{loader_version}/forge-{mc}-{loader_version}-installer.jar"
    inst = download(url, os.path.join(d, "installer.jar"))
    for flag in ("--installServer", "--install-server"):
        r = subprocess.run([java, "-jar", inst, flag, d], cwd=d, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
        if r.returncode == 0:
            break
        log(f"server installer {flag} failed:\n{r.stdout[-3000:]}")
    else:
        raise SystemExit("server installer failed")
    # run.sh reads user_jvm_args.txt; we pass our own args file instead.
    for root, _, files in os.walk(os.path.join(d, "libraries")):
        if "unix_args.txt" in files:
            rel = os.path.relpath(os.path.join(root, "unix_args.txt"), d)
            print(f"{java} @jvm_args.txt @{rel} nogui")
            return
    raise SystemExit("unix_args.txt not found")


def main():
    p = argparse.ArgumentParser()
    p.add_argument("cmd", choices=["client", "launch", "server"])
    p.add_argument("--dir", required=True)
    p.add_argument("--mc")
    p.add_argument("--loader")
    p.add_argument("--loader-version")
    p.add_argument("--id")
    p.add_argument("--java", default="java")
    p.add_argument("--user", default="Dev")
    p.add_argument("--jvm-arg", action="append", default=[])
    p.add_argument("--game-arg", action="append", default=[])
    a = p.parse_args()
    d = os.path.abspath(a.dir)
    if a.cmd == "client":
        print(install_client(d, a.mc, a.loader, a.loader_version, a.java))
    elif a.cmd == "server":
        install_server(d, a.mc, a.loader, a.loader_version, a.java)
    else:
        launch(d, a.id, a.java, a.jvm_arg, a.game_arg, a.user)


if __name__ == "__main__":
    main()
