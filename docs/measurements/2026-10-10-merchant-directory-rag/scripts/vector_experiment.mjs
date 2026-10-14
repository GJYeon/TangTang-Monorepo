// 어휘 검색 vs 벡터 검색 vs 결합 비교. 사용: OPENAI_API_KEY=... node vector_experiment.mjs <scratchDir>
// 검색 대상: 상호 DB 중 2회 이상 등장한 상호(공통 범위). 임베딩은 <scratchDir>/vec 에 캐시한다.
import fs from 'node:fs';
import path from 'node:path';

const dir = process.argv[2];
const KEY = process.env.OPENAI_API_KEY;
if (!KEY) { console.error('OPENAI_API_KEY 없음'); process.exit(1); }
const MODEL = 'text-embedding-3-small';
const DIM = 256;
const BATCH = 1000;
const vecDir = path.join(dir, 'vec');
fs.mkdirSync(vecDir, { recursive: true });

// ---------- 공통: 정규화·매핑·채점 ----------
const CORP = /(주식회사|\(주\)|㈜|주\)|\(유\)|Co\.,?\s*Ltd\.?|Corp\.?|Inc\.?)/gi;
const NON = /[^0-9A-Za-z가-힣]/g;
const norm = (s) => s.replace(CORP, '').replace(NON, '').toLowerCase();
function variants(raw) {
    const v = new Set();
    const noSuffix = raw.replace(/_\d+$/, '');
    const noParen = noSuffix.replace(/\([^)]*\)?/g, '');
    const noBranch = noParen.replace(/\s+\S*(점|호점)$/, '');
    for (const s of [raw, noSuffix, noParen, noBranch]) { const n = norm(s); if (n.length >= 2) v.add(n); }
    return [...v];
}
const codeToCat = {};
for (const l of fs.readFileSync(path.join(dir, 'industry_category_map.tsv'), 'utf8').split('\n').slice(1)) {
    const a = l.split('\t'); if (a[0]) codeToCat[a[0]] = a[2] || '';
}
const groups = { 식비: ['음식점/외식', '배달앱', '카페/간식', '편의점'], 쇼핑: ['온라인쇼핑', '패션', '뷰티'], 교통: ['대중교통', '택시/모빌리티', '주유/충전', '주차/통행료'], '주거/공과금': ['월세', '관리비', '전기·가스·수도'], 생활: ['장보기/마트', '생활용품', '세탁비', '경조사/선물', '자녀', '반려동물'], '구독/디지털': ['통신비', 'OTT', '음원', '앱·게임', '소프트웨어·클라우드'], '문화/여가': ['영화·공연·전시', '도서', '취미', '레저'], 여행: ['교통권', '숙박', '관광·여행상품'], 건강: ['병원', '약국', '운동시설', '건강관리'], '교육/자기계발': ['학원', '온라인 강의', '시험·자격증', '학습 교재'] };
const parent = {};
for (const [p, cs] of Object.entries(groups)) { parent[p] = p; for (const c of cs) parent[c] = p; }
function judge(code, seed) {
    if (!code) return 'none';
    const cat = codeToCat[code];
    if (!cat) return 'nohint';
    if (cat === seed) return 'sub_ok';
    if (cat === parent[seed]) return 'major_ok';
    if (parent[cat] === parent[seed]) return 'same_major';
    return 'wrong';
}

// ---------- 데이터 ----------
const universe = []; // [name, code]
const fullMap = new Map();
for (const line of fs.readFileSync(path.join(dir, 'out/merchant_directory.csv'), 'utf8').split('\n')) {
    if (!line) continue;
    const [name, code, cnt] = line.split(',');
    fullMap.set(name, code);
    if (+cnt >= 2) universe.push([name, code]);
}
const subMap = new Map(universe);
const rows = fs.readFileSync(path.join(dir, 'seed_rows.tsv'), 'utf8').split('\n').filter(Boolean)
    .map((l) => l.split('\t')).map(([m, c]) => [m, c.replace(/'$/, '').replace('시험/자격증', '시험·자격증')]);
const evalSet = new Map(); // "m\tcat" -> txCnt
for (const [m, c] of rows) evalSet.set(m + '\t' + c, (evalSet.get(m + '\t' + c) || 0) + 1);
console.error(`universe=${universe.length} eval=${evalSet.size} tx=${rows.length}`);

// ---------- 어휘 검색 ----------
function lexical(raw, map) {
    const vs = variants(raw);
    for (const n of vs) if (map.has(n)) return { how: 'EXACT', key: n, code: map.get(n) };
    let best = null;
    for (const n of vs) for (let len = n.length - 1; len >= 3; len--) {
        const k = n.slice(0, len);
        if (map.has(k)) { if (!best || len > best.key.length) best = { how: 'PREFIX', key: k, code: map.get(k) }; break; }
    }
    return best;
}

// ---------- 임베딩 ----------
async function embed(texts) {
    for (let attempt = 0; ; attempt++) {
        const res = await fetch('https://api.openai.com/v1/embeddings', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${KEY}` },
            body: JSON.stringify({ model: MODEL, input: texts, dimensions: DIM }),
        });
        if (res.ok) {
            const j = await res.json();
            return { vecs: j.data.sort((a, b) => a.index - b.index).map((d) => d.embedding), tokens: j.usage.total_tokens };
        }
        const body = await res.text();
        if ((res.status === 429 || res.status >= 500) && attempt < 6) {
            await new Promise((r) => setTimeout(r, 2000 * 2 ** attempt));
            continue;
        }
        throw new Error(`embeddings ${res.status}: ${body.slice(0, 300)}`);
    }
}

let totalTokens = 0;
const uniVec = new Float32Array(universe.length * DIM);
const nBatches = Math.ceil(universe.length / BATCH);
const tEmbed = Date.now();
const queue = [...Array(nBatches).keys()];
async function worker() {
    while (queue.length) {
        const b = queue.shift();
        const file = path.join(vecDir, `u${b}.bin`);
        let buf;
        if (fs.existsSync(file)) {
            buf = new Float32Array(fs.readFileSync(file).buffer.slice(0));
        } else {
            const slice = universe.slice(b * BATCH, (b + 1) * BATCH).map(([n]) => n);
            const { vecs, tokens } = await embed(slice);
            totalTokens += tokens;
            buf = new Float32Array(vecs.length * DIM);
            vecs.forEach((v, i) => buf.set(v, i * DIM));
            fs.writeFileSync(file, Buffer.from(buf.buffer));
            if (b % 25 === 0) console.error(`batch ${b}/${nBatches} tokens=${totalTokens}`);
        }
        uniVec.set(buf, b * BATCH * DIM);
    }
}
await Promise.all(Array.from({ length: 4 }, worker));
const embedSec = (Date.now() - tEmbed) / 1000;

// 질의: 가맹점명의 정리된 변형(괄호·접미어·지점 제거) 하나를 임베딩
const queries = [...evalSet.keys()].map((k) => k.split('\t')[0]);
const qText = queries.map((m) => { const vs = variants(m); return vs[vs.length - 1] || norm(m) || m; });
const qRes = await embed(qText);
totalTokens += qRes.tokens;

function topK(q, k) {
    const best = [];
    for (let i = 0; i < universe.length; i++) {
        let s = 0;
        const off = i * DIM;
        for (let d = 0; d < DIM; d++) s += q[d] * uniVec[off + d];
        if (best.length < k || s > best[best.length - 1][1]) {
            best.push([i, s]); best.sort((a, b) => b[1] - a[1]); if (best.length > k) best.pop();
        }
    }
    return best;
}

// ---------- 평가 ----------
const methods = ['L_full', 'L_sub', 'V_top1', 'V_vote5', 'H_sub'];
const score = Object.fromEntries(methods.map((m) => [m, {}]));
const add = (m, k, n) => (score[m][k] = (score[m][k] || 0) + n);
const detail = [];
const tSearch = Date.now();
[...evalSet].forEach(([k, txCnt], qi) => {
    const [m, seed] = k.split('\t');
    const lf = lexical(m, fullMap);
    const ls = lexical(m, subMap);
    const top = topK(qRes.vecs[qi], 5);
    const v1 = universe[top[0][0]][1];
    const votes = {};
    for (const [i, s] of top) { const c = codeToCat[universe[i][1]]; if (c) votes[c] = (votes[c] || 0) + s; }
    const voteCat = Object.entries(votes).sort((a, b) => b[1] - a[1])[0]?.[0];
    const voteCode = voteCat ? universe[top.find(([i]) => codeToCat[universe[i][1]] === voteCat)[0]][1] : null;
    const r = {
        L_full: judge(lf?.code, seed), L_sub: judge(ls?.code, seed),
        V_top1: judge(v1, seed), V_vote5: voteCode ? judge(voteCode, seed) : 'nohint',
        H_sub: ls ? judge(ls.code, seed) : judge(v1, seed),
    };
    for (const mm of methods) add(mm, r[mm], txCnt);
    detail.push([txCnt, m, seed, ls ? `${ls.how}:${ls.key}>${codeToCat[ls.code] || '-'}` : '-',
        `${universe[top[0][0]][0]}(${top[0][1].toFixed(2)})>${codeToCat[v1] || '-'}`, r.L_sub, r.V_top1].join('\t'));
});
const searchMs = (Date.now() - tSearch) / evalSet.size;

fs.writeFileSync(path.join(dir, 'vector_detail.tsv'), 'tx\tmerchant\tseed\tlexical_sub\tvector_top1\tL_sub\tV_top1\n' + detail.join('\n'));
const total = rows.length;
console.log('방식\t힌트있음\t소분류정답\t정답률(힌트기준)\t전체대비정답\t오답\t힌트없음');
for (const m of methods) {
    const s = score[m];
    const hinted = (s.sub_ok || 0) + (s.major_ok || 0) + (s.same_major || 0) + (s.wrong || 0);
    console.log(`${m}\t${hinted}\t${s.sub_ok || 0}\t${((100 * (s.sub_ok || 0)) / hinted).toFixed(1)}%\t${((100 * (s.sub_ok || 0)) / total).toFixed(1)}%\t${s.wrong || 0}\t${(s.none || 0) + (s.nohint || 0)}`);
}
console.log(`tokens=${totalTokens} embedSec=${embedSec.toFixed(0)} bruteForceMsPerQuery=${searchMs.toFixed(1)}`);
