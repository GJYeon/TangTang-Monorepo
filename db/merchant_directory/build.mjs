// 상가(상권)정보 원본 CSV → tbl_merchant_directory 적재용 SQL.
// 사용: node build.mjs <원본CSV폴더> <출력폴더>
// 정규화 규칙은 apps/api 의 MerchantNameNormalizer 와 같아야 한다(다르면 조회 키가 어긋난다).
import fs from 'node:fs';
import path from 'node:path';
import readline from 'node:readline';
import zlib from 'node:zlib';
import { pathToFileURL } from 'node:url';

const CORPORATE_MARKERS = /(주식회사|\(주\)|㈜|Co\.,?\s*Ltd\.?|Corp\.?|Inc\.?)/gi;
const NON_ALNUM_KOREAN = /[^0-9A-Za-z가-힣]/g;
const MAX_NAME_LENGTH = 100;
const INSERT_BATCH = 1000;
const COLUMN_NAME = 1;
const COLUMN_INDUSTRY_CODE = 7;
const MIN_COLUMNS = 39;

export function normalize(raw) {
    return raw.replace(CORPORATE_MARKERS, '').replace(NON_ALNUM_KOREAN, '').toLowerCase();
}

export function aggregate(rows) {
    const byName = new Map();
    for (const [rawName, code] of rows) {
        const name = normalize(rawName);
        if (!name || name.length > MAX_NAME_LENGTH) continue;
        let counts = byName.get(name);
        if (!counts) {
            counts = new Map();
            byName.set(name, counts);
        }
        counts.set(code, (counts.get(code) || 0) + 1);
    }
    const result = new Map();
    for (const [name, counts] of byName) {
        let total = 0;
        let topCode = '';
        let topCount = 0;
        for (const [code, n] of counts) {
            total += n;
            if (n > topCount) {
                topCount = n;
                topCode = code;
            }
        }
        result.set(name, { code: topCode, count: total, share: topCount / total });
    }
    return result;
}

const quote = (s) => "'" + s.replace(/'/g, "''") + "'";

export function toSql(directory) {
    const lines = [
        '-- 생성 파일 — db/merchant_directory/build.mjs. 직접 고치지 말 것.',
        'SET NAMES utf8mb4;',
        'TRUNCATE TABLE tbl_merchant_directory;',
    ];
    const values = [...directory].map(([name, e]) =>
        `(${quote(name)}, ${quote(e.code)}, ${e.count}, ${e.share.toFixed(3)})`);
    for (let i = 0; i < values.length; i += INSERT_BATCH) {
        lines.push('INSERT INTO tbl_merchant_directory '
            + '(merchant_name_normalized, industry_code, store_count, top_share) VALUES');
        lines.push(values.slice(i, i + INSERT_BATCH).join(',\n') + ';');
    }
    return lines.join('\n') + '\n';
}

function parseCsvLine(line) {
    const out = [];
    let cur = '';
    let quoted = false;
    for (let i = 0; i < line.length; i++) {
        const c = line[i];
        if (quoted) {
            if (c === '"') {
                if (line[i + 1] === '"') {
                    cur += '"';
                    i++;
                } else {
                    quoted = false;
                }
            } else {
                cur += c;
            }
        } else if (c === '"') {
            quoted = true;
        } else if (c === ',') {
            out.push(cur);
            cur = '';
        } else {
            cur += c;
        }
    }
    out.push(cur);
    return out;
}

async function readRows(srcDir) {
    const rows = [];
    const files = fs.readdirSync(srcDir).filter((f) => f.endsWith('.csv'));
    for (const file of files) {
        const input = fs.createReadStream(path.join(srcDir, file), 'utf8');
        const rl = readline.createInterface({ input, crlfDelay: Infinity });
        let header = true;
        for await (const line of rl) {
            if (header) {
                header = false;
                continue;
            }
            const cols = parseCsvLine(line);
            if (cols.length < MIN_COLUMNS) continue;
            rows.push([cols[COLUMN_NAME], cols[COLUMN_INDUSTRY_CODE]]);
        }
        console.error(`읽음: ${file}`);
    }
    return rows;
}

async function main() {
    const [, , srcDir, outDir] = process.argv;
    if (!srcDir || !outDir) {
        console.error('사용: node build.mjs <원본CSV폴더> <출력폴더>');
        process.exit(1);
    }
    fs.mkdirSync(outDir, { recursive: true });
    const directory = aggregate(await readRows(srcDir));
    const sql = toSql(directory);
    const sqlPath = path.join(outDir, 'merchant_directory.sql');
    fs.writeFileSync(sqlPath, sql);
    fs.writeFileSync(sqlPath + '.gz', zlib.gzipSync(sql, { level: 9 }));
    console.error(`상호 ${directory.size}개 → ${sqlPath}(.gz)`);
}

if (import.meta.url === pathToFileURL(process.argv[1]).href) {
    await main();
}
