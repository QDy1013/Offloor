// 测试运行器：给只有"跑测试"权限的执行端用。只接受测试名，不接受任意命令。
// 用法：node eval/run_test.mjs test_dedup_rank      （Python 用例，eval/test_*.py）
//       node eval/run_test.mjs Step11Test           （Java 用例，android/test/StepNTest.java，也含 EngineTest）
// 输出最后 40 行，退出码和用例一致。
import { spawnSync } from 'node:child_process';
import { mkdtempSync, existsSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const name = process.argv[2] || '';
const PYTHON = process.env.NOPALOU_PYTHON || (process.platform === 'win32' ? 'python' : 'python3');
const tail = s => s.trim().split(/\r?\n/).slice(-40).join('\n');

function finish(r) {
  console.log(tail((r.stdout || '') + (r.stderr || '')));
  process.exit(r.status === 0 ? 0 : 1);
}

if (/^test_[a-z0-9_]+$/.test(name)) {
  const file = path.join(root, 'eval', name + '.py');
  if (!existsSync(file)) { console.log('没有这个用例：' + name); process.exit(2); }
  finish(spawnSync(PYTHON, ['-I', '-X', 'utf8', file], { cwd: root, encoding: 'utf8', timeout: 120000 }));
} else if (/^(EngineTest|Step\d+Test)$/.test(name)) {
  const src = path.join(root, 'android/test', name + '.java');
  if (!existsSync(src)) { console.log('没有这个用例：' + name); process.exit(2); }
  const out = mkdtempSync(path.join(tmpdir(), 'nopalou-test-'));
  const engine = path.join(root, 'android/app/src/com/nopalou/Engine.java');
  const filter = path.join(root, 'android/app/src/com/nopalou/NotifFilter.java');
  const demo = path.join(root, 'android/app/src/com/nopalou/DemoScenario.java');
  const reminder = path.join(root, 'android/app/src/com/nopalou/ReminderPlan.java');
  const today = path.join(root, 'android/app/src/com/nopalou/TodayView.java');
  const clash = path.join(root, 'android/app/src/com/nopalou/ClashPlan.java');
  const parser = path.join(root, 'android/app/src/com/nopalou/NotifParser.java');
  const diag = path.join(root, 'android/app/src/com/nopalou/DiagLog.java');
  const health = path.join(root, 'android/app/src/com/nopalou/HealthCheck.java');
  const diff = path.join(root, 'android/app/src/com/nopalou/ChangeDiff.java');
  const sections = path.join(root, 'android/app/src/com/nopalou/SectionTimes.java');
  const files = [engine, ...[filter, demo, reminder, today, clash, parser, diag, health, diff, sections].filter(existsSync), src];
  const c = spawnSync('javac', ['-J-Duser.language=en', '-nowarn', '-encoding', 'UTF-8', '-d', out, ...files], { encoding: 'utf8', timeout: 120000 });
  if (c.status !== 0) finish(c);
  const args = ['-Dfile.encoding=UTF-8', '-Dstdout.encoding=UTF-8', '-cp', out, 'com.nopalou.' + name, path.join(root, 'android')];
  if (name === 'EngineTest') args.push(path.join(root, 'android/replay/assets/messages.txt'));
  if (name === 'EngineTest') args[args.length - 2] = path.join(root, 'android/app/assets');
  finish(spawnSync('java', args, { encoding: 'utf8', timeout: 120000 }));
} else {
  console.log('只能跑测试：test_xxx（Python）或 StepNTest / EngineTest（Java）');
  process.exit(2);
}
