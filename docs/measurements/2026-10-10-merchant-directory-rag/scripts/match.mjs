// 시드 가맹점명 → 상호 DB 매칭률 측정. 사용: node match.mjs <scratchDir>
import fs from 'node:fs';
import path from 'node:path';

const dir = process.argv[2];
const CORP = /(주식회사|\(주\)|㈜|주\)|\(유\)|Co\.,?\s*Ltd\.?|Corp\.?|Inc\.?)/gi;
const NON = /[^0-9A-Za-z가-힣]/g;
const norm = (s) => s.replace(CORP, '').replace(NON, '').toLowerCase();

// 상호 DB 로드: name -> {code, cnt, share}
const dirMap = new Map();
for (const line of fs.readFileSync(path.join(dir, 'out/merchant_directory.csv'), 'utf8').split('\n')) {
    if (!line) continue;
    const [name, code, cnt, share] = line.split(',');
    dirMap.set(name, { code, cnt: +cnt, share: +share });
}
const sortedNames = [...dirMap.keys()].sort();
const ind = new Map();
for (const line of fs.readFileSync(path.join(dir, 'out/industry_codes.csv'), 'utf8').split('\n')) {
    const [code, , mid, small] = line.split(',');
    ind.set(code, `${mid}>${small}`);
}

// 변형: 원본 / 괄호 내용 제거 / 끝의 _숫자 제거 / 공백 뒤 지점명 제거
function variants(raw) {
    const v = new Set();
    const noSuffix = raw.replace(/_\d+$/, '');
    const noParen = noSuffix.replace(/\([^)]*\)?/g, '');
    const noBranch = noParen.replace(/\s+\S*(점|호점)$/, '');
    for (const s of [raw, noSuffix, noParen, noBranch]) {
        const n = norm(s);
        if (n.length >= 2) v.add(n);
    }
    return [...v];
}

function lowerBound(arr, x) {
    let lo = 0, hi = arr.length;
    while (lo < hi) { const m = (lo + hi) >> 1; if (arr[m] < x) lo = m + 1; else hi = m; }
    return lo;
}

function match(raw) {
    const vs = variants(raw);
    // 1) 정확 일치
    for (const n of vs) {
        const hit = dirMap.get(n);
        if (hit) return { how: 'exact', key: n, ...hit };
    }
    // 2) DB 상호가 가맹점명의 앞부분 (체인+지점명). 가장 긴 것, 3자 이상
    let best = null;
    for (const n of vs) {
        for (let len = n.length - 1; len >= 3; len--) {
            const hit = dirMap.get(n.slice(0, len));
            if (hit) { if (!best || len > best.key.length) best = { how: 'prefix', key: n.slice(0, len), ...hit }; break; }
        }
    }
    if (best) return best;
    // 3) 가맹점명이 잘린 경우: 가맹점명으로 시작하는 DB 상호들의 업종 분포 합산 (4자 이상)
    for (const n of vs) {
        if (n.length < 4) continue;
        const counts = new Map();
        let total = 0;
        for (let i = lowerBound(sortedNames, n); i < sortedNames.length && sortedNames[i].startsWith(n); i++) {
            const e = dirMap.get(sortedNames[i]);
            counts.set(e.code, (counts.get(e.code) || 0) + e.cnt);
            total += e.cnt;
        }
        if (total > 0) {
            const [code, c] = [...counts].sort((a, b) => b[1] - a[1])[0];
            return { how: 'truncated', key: n + '*', code, cnt: total, share: c / total };
        }
    }
    return null;
}

const rows = fs.readFileSync(path.join(dir, 'seed_rows.tsv'), 'utf8').split('\n').filter(Boolean)
    .map((l) => l.split('\t')).map(([m, c]) => [m, c.replace(/'$/, '')]);
const byMerchant = new Map();
for (const [m, c] of rows) {
    const k = m + '\t' + c;
    byMerchant.set(k, (byMerchant.get(k) || 0) + 1);
}

const out = [];
const sum = { exact: [0, 0], prefix: [0, 0], truncated: [0, 0], none: [0, 0] };
for (const [k, txCnt] of byMerchant) {
    const [m, cat] = k.split('\t');
    const r = match(m);
    const how = r ? r.how : 'none';
    sum[how][0]++;
    sum[how][1] += txCnt;
    out.push([how, txCnt, m, cat, r ? r.key : '', r ? ind.get(r.code) : '', r ? r.cnt : '', r ? r.share.toFixed(2) : ''].join('\t'));
}
fs.writeFileSync(path.join(dir, 'match_result.tsv'), out.join('\n'));
console.log('방식\t고유가맹점\t거래건수');
for (const [k, [u, t]] of Object.entries(sum)) console.log(`${k}\t${u}\t${t}`);
console.log(`total\t${byMerchant.size}\t${rows.length}`);
