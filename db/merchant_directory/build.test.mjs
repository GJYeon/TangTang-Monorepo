import { test } from 'node:test';
import assert from 'node:assert/strict';
import { normalize, aggregate, toSql } from './build.mjs';

test('normalize 는 MerchantNameNormalizer 와 같은 규칙을 쓴다', () => {
    assert.equal(normalize('(주)스타벅스 강남점'), '스타벅스강남점');
    assert.equal(normalize('CU 역삼점'), 'cu역삼점');
    assert.equal(normalize('Coupang Co., Ltd.'), 'coupang');
    assert.equal(normalize('㈜ 행복식당!!'), '행복식당');
});

test('aggregate 는 최다 업종·점포 수·비율을 계산하고 빈 이름은 버린다', () => {
    const result = aggregate([
        ['행복식당', 'I20101'], ['행복식당', 'I20101'], ['행복식당', 'I20101'], ['행복식당', 'I21104'],
        ['스타벅스', 'I21201'], ['!!', 'I21201'],
    ]);
    assert.deepEqual(result.get('행복식당'), { code: 'I20101', count: 4, share: 0.75 });
    assert.deepEqual(result.get('스타벅스'), { code: 'I21201', count: 1, share: 1 });
    assert.equal(result.size, 2);
});

test('aggregate 는 100자를 넘는 정규화 이름을 버린다', () => {
    const result = aggregate([['가'.repeat(101), 'I20101'], ['가'.repeat(100), 'I20101']]);
    assert.equal(result.size, 1);
});

test('toSql 은 TRUNCATE 후 1,000행 단위 INSERT 를 만든다', () => {
    const directory = new Map();
    for (let i = 0; i < 1001; i++) directory.set('상호' + i, { code: 'I21201', count: 1, share: 1 });
    const sql = toSql(directory);
    assert.match(sql, /^SET NAMES utf8mb4;/m);
    assert.match(sql, /^TRUNCATE TABLE tbl_merchant_directory;/m);
    assert.equal(sql.match(/^INSERT INTO tbl_merchant_directory/gm).length, 2);
    assert.match(sql, /\('상호0', 'I21201', 1, 1\.000\)/);
});

test('toSql 은 작은따옴표를 이스케이프한다', () => {
    const sql = toSql(new Map([["a'b", { code: 'I21201', count: 2, share: 0.5 }]]));
    assert.match(sql, /\('a''b', 'I21201', 2, 0\.500\)/);
});
