// Android 渲染层打包脚本：把 android/entry.ts 及其依赖打成 WebView 可用的单文件 bundle。
// 用法：node build.mjs [--watch] [--dev]
//
// 关键边界处理（其余依赖均干净，无需 alias）：
//   · pet.ts 第 72 行 `import { petBridge } from './settings'`：
//     settings.ts 含 DSH 平台 primitives 的 React 设置页组件，不能进 bundle。
//     这里用 onResolve 插件把「pet.ts 里的 ./settings」定向替换成 android/petbridge.ts
//     （同形状的单例：{ current, reload, template }）。
//   · pet.ts 的 JSX 是手写 h(...) 调用形态，不经过 JSX 语法转换；
//     h 由入口从 react/jsx-runtime 取到后注入 makePetUI。因此无需配置 jsx 选项。
//   · react / react-dom 走真实依赖（package.json 已安装 18.3.1）打进 bundle，
//     WebView 内无 node_modules，必须是自包含单文件 → bundle: true, format: 'iife'。
import { build, context } from 'esbuild';
import { fileURLToPath } from 'node:url';
import { dirname, resolve } from 'node:path';

const here = dirname(fileURLToPath(import.meta.url));
const abs = (p) => resolve(here, p);

const watch = process.argv.includes('--watch');
const dev = process.argv.includes('--dev');

/** 把某个 importer 内的相对导入定向到指定绝对路径 */
const redirectPlugin = (mapping) => ({
  name: 'dsh-pet-redirect',
  setup(b) {
    for (const [importerSuffix, filterRe, target] of mapping) {
      b.onResolve({ filter: filterRe }, (args) => {
        if (args.importer.replaceAll('\\', '/').endsWith(importerSuffix)) {
          return { path: abs(target) };
        }
        return undefined;
      });
    }
  },
});

/** @type {import('esbuild').BuildOptions} */
const options = {
  entryPoints: [abs('android/entry.ts')],
  outfile: abs('dist/dsh-pet.js'),
  bundle: true,
  format: 'iife',
  globalName: 'DshPetApp',
  platform: 'browser',
  target: ['es2020', 'chrome100'],
  // WebView 无 node_modules：react 的 process.env.NODE_ENV 需显式注入
  define: {
    'process.env.NODE_ENV': dev ? '"development"' : '"production"',
  },
  // react-dom 生产中走压缩包；显式避免误留 dev 分支
  minify: !dev,
  sourcemap: dev ? 'inline' : true,
  legalComments: 'eof', // MIT 版权声明保留在产物末尾
  charset: 'utf8',
  logLevel: 'info',
  plugins: [
    redirectPlugin([
      // pet.ts 里的 ./settings → android/petbridge.ts（只匹配相对导入字面量）
      ['/src/client/pet.ts', /^\.\/settings$/, 'android/petbridge.ts'],
    ]),
  ],
};

if (watch) {
  const ctx = await context(options);
  await ctx.watch();
  console.log('[build] watching…');
} else {
  const result = await build(options);
  console.log('[build] done → dist/dsh-pet.js');
  if (result.errors.length) process.exit(1);
}