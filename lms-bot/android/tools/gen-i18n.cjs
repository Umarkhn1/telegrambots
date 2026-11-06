// Переносит словари мини-приложения (webapp/src/lib/i18n.ts и ti18n.ts) в assets Android-приложения,
// чтобы тексты в боте, мини-приложении и Android совпадали. Запуск: node android/tools/gen-i18n.cjs
const fs = require('fs');
const path = require('path');
const Module = require('module');
const root = path.resolve(__dirname, '..', '..');
const web = path.join(root, 'webapp');
const ts = require(path.join(web, 'node_modules', 'typescript'));

function load(file, extra) {
  const src = fs.readFileSync(path.join(web, 'src', 'lib', file), 'utf8');
  let js = ts.transpileModule(src, { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020 } }).outputText;
  js += '\n' + extra;
  const m = new Module(path.join(web, 'src', 'lib', file), module);
  m.filename = path.join(web, 'src', 'lib', file);
  m.paths = Module._nodeModulePaths(path.join(web, 'src', 'lib'));
  m._compile(js, m.filename);
  return m.exports;
}

const main = load('i18n.ts', 'module.exports.__dicts = dicts; module.exports.__cal = { MONTHS, MONTHS_SHORT };');
require.cache[path.join(web, 'src', 'lib', 'i18n.ts')] = { exports: main, loaded: true, id: 'i18n' };
require.extensions['.ts'] = require.extensions['.ts'] || ((m, f) => m._compile('', f));
const teacher = load('ti18n.ts', 'module.exports.__dicts = dicts;');
const out = path.join(root, 'android', 'app', 'src', 'main', 'assets', 'i18n');
const all = {};
for (const lang of ['ru', 'uz_lat', 'uz_cyr', 'en']) {
  all[lang] = {
    strings: main.__dicts[lang],
    teacher: teacher.__dicts[lang],
    months: main.__cal.MONTHS[lang],
    monthsShort: main.__cal.MONTHS_SHORT[lang],
    weekdays: main.WEEKDAYS[lang],
    weekdaysShort: main.WEEKDAYS_SHORT[lang],
    name: main.LANG_NAMES[lang],
  };
}
fs.writeFileSync(path.join(out, 'web.json'), JSON.stringify(all, null, 1));
console.log('keys:', Object.keys(all.ru.strings).length, 'teacher:', Object.keys(all.ru.teacher).length);
