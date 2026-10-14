// 상가(상권)정보 CSV → 상호명별 업종 분포 집계. 사용: node aggregate.mjs <csvDir> <outDir>
import fs from 'node:fs';
import path from 'node:path';
import readline from 'node:readline';
import zlib from 'node:zlib';

const [, , srcDir, outDir] = process.argv;
fs.mkdirSync(outDir, { recursive: true });

// MerchantNameNormalizer.java 와 같은 규칙
const CORP = /(주식회사|\(주\)|㈜|Co\.,?\s*Ltd\.?|Corp\.?|Inc\.?)/gi;
const NON = /[^0-9A-Za-z가-힣]/g;
const norm = (s) => s.replace(CORP, '').replace(NON, '').toLowerCase();

function parseLine(line) {
    const out = [];
    let cur = '';
    let q = false;
    for (let i = 0; i < line.length; i++) {
        const c = line[i];
        if (q) {
            if (c === '"') {
                if (line[i + 1] === '"') { cur += '"'; i++; } else { q = false; }
            } else { cur += c; }
        } else if (c === '"') { q = true; }
        else if (c === ',') { out.push(cur); cur = ''; }
        else { cur += c; }
    }
    out.push(cur);
    return out;
}

const byName = new Map();       // name -> Map(code -> cnt)
const branchKeys = new Set();   // name+지점명
const industries = new Map();   // 소분류코드 -> [대, 중, 소 이름, cnt]
let rows = 0, emptyName = 0, bad = 0;

const files = fs.readdirSync(srcDir).filter((f) => f.endsWith('.csv'));
for (const f of files) {
    const rl = readline.createInterface({ input: fs.createReadStream(path.join(srcDir, f), 'utf8'), crlfDelay: Infinity });
    let header = true;
    for await (const raw of rl) {
        if (header) { header = false; continue; }
        const c = parseLine(raw);
        if (c.length < 39) { bad++; continue; }
        rows++;
        const name = norm(c[1]);
        const code = c[7];
        const ind = industries.get(code);
        if (ind) ind[3]++; else industries.set(code, [c[4], c[6], c[8], 1]);
        if (!name) { emptyName++; continue; }
        let m = byName.get(name);
        if (!m) { m = new Map(); byName.set(name, m); }
        m.set(code, (m.get(code) || 0) + 1);
        const br = norm(c[2]);
        if (br) branchKeys.add(name + br);
    }
    console.error(`done ${f} rows=${rows}`);
}

// 집계 출력: name,top_code,cnt,share,distinct_codes
const outPath = path.join(outDir, 'merchant_directory.csv');
const ws = fs.createWriteStream(outPath);
const cntBuckets = { '1': 0, '2-4': 0, '5-19': 0, '20+': 0 };
const shareBuckets = { '1.0': 0, '>=0.8': 0, '>=0.5': 0, '<0.5': 0 };
let confident = 0;
for (const [name, m] of byName) {
    let total = 0, topCode = '', topCnt = 0;
    for (const [code, n] of m) { total += n; if (n > topCnt) { topCnt = n; topCode = code; } }
    const share = topCnt / total;
    ws.write(`${name},${topCode},${total},${share.toFixed(3)},${m.size}\n`);
    cntBuckets[total === 1 ? '1' : total < 5 ? '2-4' : total < 20 ? '5-19' : '20+']++;
    shareBuckets[share === 1 ? '1.0' : share >= 0.8 ? '>=0.8' : share >= 0.5 ? '>=0.5' : '<0.5']++;
    if (share >= 0.8) confident++;
}
await new Promise((r) => ws.end(r));

const indPath = path.join(outDir, 'industry_codes.csv');
fs.writeFileSync(indPath, [...industries].sort().map(([k, v]) => `${k},${v.join(',')}`).join('\n'));

const gz = zlib.gzipSync(fs.readFileSync(outPath), { level: 9 });
fs.writeFileSync(outPath + '.gz', gz);

console.log(JSON.stringify({
    rows, bad, emptyName,
    distinctNames: byName.size,
    distinctNameBranch: branchKeys.size,
    industryCodes: industries.size,
    cntBuckets, shareBuckets, confidentShare80: confident,
    outMB: (fs.statSync(outPath).size / 1048576).toFixed(1),
    gzMB: (gz.length / 1048576).toFixed(1),
}, null, 2));
