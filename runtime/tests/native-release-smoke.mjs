/** Offline Linux ARM64 subprocess contract. No owner home, model turns or device operations. */
import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { createHash } from "node:crypto";
import { mkdtemp, mkdir, readFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { createInterface } from "node:readline";

const input = process.argv[2];
assert.ok(input, "Pass isolated public-fixture directory");
const lock = JSON.parse(await readFile(join(input, "runtime.lock.json"), "utf8"));
const source = await readFile(join(input, "CodexAssistantProfile.kt"), "utf8");
const settings = await readFile(join(input, "HansSettings.kt"), "utf8");
const constants = Object.fromEntries([...settings.matchAll(/const val (DEFAULT_MODEL|DEFAULT_REASONING_EFFORT) = "([^"]+)"/g)].map(m => [m[1], m[2]]));
const special = {"HansSettings.DEFAULT_MODEL": constants.DEFAULT_MODEL,
  "HansSettings.DEFAULT_REASONING_EFFORT": constants.DEFAULT_REASONING_EFFORT,
  "reasoningSummary.wireValue": "concise", "personality.wireValue": "friendly"};
const profile = Object.fromEntries([...source.matchAll(/^\s*"([^"\n]+)" to ([^,\n]+),/gm)]
  .map(([, key, value]) => [key, value in special ? special[value] : JSON.parse(value)]));
assert.ok(Object.keys(profile).length >= 28);
const scratch = await mkdtemp(join(tmpdir(), "hans-native-release-"));
await mkdir(join(scratch, "workspace"));
await mkdir(join(scratch, "codex"));
// Child homes are new synthetic fixtures, never the owner's runtime/config directory.
const env = {PATH: "/usr/bin:/bin", HOME: scratch, CODEX_HOME: join(scratch, "codex"), TMPDIR: scratch};
const children = [];
const checks = [];
function check(name, ok) { assert.ok(ok, name); checks.push(name); }
function launch(binary, args) {
  const child = spawn(join(input, binary), args, {env, cwd: join(scratch, "workspace"), stdio: ["pipe", "pipe", "pipe"]});
  children.push(child);
  child.syntheticStderr = "";
  child.stderr.on("data", chunk => { child.syntheticStderr = (child.syntheticStderr + chunk).slice(-4000); });
  return child;
}
async function stop(child) {
  if (child.exitCode !== null || child.signalCode !== null) return;
  child.stdin.end();
  const exited = new Promise(resolve => child.once("exit", resolve));
  const kill = setTimeout(() => child.kill("SIGKILL"), 3000);
  child.kill("SIGTERM");
  await exited;
  clearTimeout(kill);
}
let status = "failed";
try {
  for (const key of ["runtime", "codeModeHost"]) {
    const pin = lock[key];
    const raw = await readFile(join(input, pin.extractedName));
    check(`${key}-digest`, createHash("sha256").update(raw).digest("hex") === pin.extractedSha256 && raw.length === pin.extractedBytes);
  }
  const host = launch(lock.codeModeHost.extractedName, ["--listen", "grpc://127.0.0.1:0"]);
  const lines = createInterface({input: host.stdout});
  const endpoint = await new Promise((resolve, reject) => {
    const timeout = setTimeout(() => reject(new Error("Code Mode host endpoint timeout")), 10000);
    lines.once("line", value => {clearTimeout(timeout); resolve(value);});
    host.once("error", reject);
  });
  check("loopback-code-mode-host", /^http:\/\/127\.0\.0\.1:[1-9][0-9]{0,4}$/.test(endpoint));
  const args = ["--strict-config", ...Object.entries(profile).flatMap(([key, value]) => ["-c", `${key}=${JSON.stringify(value)}`]), "--code-mode-host", endpoint];
  const server = launch(lock.runtime.extractedName, args);
  const pending = new Map();
  createInterface({input: server.stdout}).on("line", raw => {
    const result = JSON.parse(raw);
    if (pending.has(result.id)) pending.get(result.id)(result);
  });
  let sequence = 0;
  async function rpc(method, params) {
    const id = ++sequence;
    return new Promise((resolve, reject) => {
      const timeout = setTimeout(() => {pending.delete(id); reject(new Error(`RPC timeout: ${method}; synthetic process log: ${server.syntheticStderr}`));}, 15000);
      pending.set(id, result => {clearTimeout(timeout); pending.delete(id); resolve(result);});
      server.stdin.write(JSON.stringify({id, method, params}) + "\n");
    });
  }
  const initialized = await rpc("initialize", {clientInfo: {name: "hans_native_release_smoke", version: "1"}, capabilities: {experimentalApi: true}});
  check("initialize-experimental", !!initialized.result);
  server.stdin.write('{"method":"initialized"}\n');
  const configured = await rpc("config/read", {includeLayers: false, cwd: join(scratch, "workspace")});
  for (const [key, expected] of Object.entries(profile)) {
    const actual = key.split(".").reduce((value, part) => value?.[part], configured.result?.config);
    check(`effective-${key}`, actual === expected);
  }
  const probe = {type: "function", name: "probe", description: "Never executed.", inputSchema: {type: "object", properties: {}, additionalProperties: false}};
  for (const [label, description, count, accepted] of [
    ["ascii1024", "x".repeat(1024), 1, true], ["ascii1025", "x".repeat(1025), 1, false],
    ["unicode1024", "🙂".repeat(1024), 1, true], ["unicode1025", "🙂".repeat(1025), 1, false],
    ["namespaces17", "Probe", 17, true],
  ]) {
    const tools = Array.from({length: count}, (_, i) => ({type: "namespace", name: `hans_probe_${i}`, description, tools: [probe]}));
    const response = await rpc("thread/start", {model: constants.DEFAULT_MODEL, allowProviderModelFallback: false,
      config: {model_reasoning_effort: constants.DEFAULT_REASONING_EFFORT}, ephemeral: true,
      cwd: join(scratch, "workspace"), dynamicTools: tools});
    check(label, !!response.result === accepted && (accepted || response.error?.code === -32600));
    if (accepted) check(`${label}-effective-model-effort`, response.result.model === constants.DEFAULT_MODEL && response.result.reasoningEffort === constants.DEFAULT_REASONING_EFFORT);
  }
  status = "passed";
} finally {
  for (const child of children.reverse()) await stop(child);
  console.log(JSON.stringify({schema: "hans-native-release-smoke-v1", runtimeVersion: lock.runtime.version,
    platform: "linux-arm64-not-android", status, modelTurns: 0, ownerAuthAccess: false,
    profileSourceSha256: createHash("sha256").update(source).digest("hex"),
    checks, childrenStopped: children.every(child => child.exitCode !== null || child.signalCode !== null)}, null, 2));
}
