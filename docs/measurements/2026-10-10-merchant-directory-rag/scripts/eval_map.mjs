import fs from 'node:fs';
const dir = process.argv[2];
const parent = {};
const groups = {식비:['음식점/외식','배달앱','카페/간식','편의점'],쇼핑:['온라인쇼핑','패션','뷰티'],교통:['대중교통','택시/모빌리티','주유/충전','주차/통행료'],'주거/공과금':['월세','관리비','전기·가스·수도'],생활:['장보기/마트','생활용품','세탁비','경조사/선물','자녀','반려동물'],'구독/디지털':['통신비','OTT','음원','앱·게임','소프트웨어·클라우드'],'문화/여가':['영화·공연·전시','도서','취미','레저'],여행:['교통권','숙박','관광·여행상품'],건강:['병원','약국','운동시설','건강관리'],'교육/자기계발':['학원','온라인 강의','시험·자격증','학습 교재']};
for (const [p, cs] of Object.entries(groups)) { parent[p] = p; for (const c of cs) parent[c] = p; }
const nameToCat = {};
const codeOf = {};
for (const l of fs.readFileSync(dir + '/out/industry_codes.csv', 'utf8').split('\n')) { const a = l.split(','); codeOf[`${a[2]}>${a[3]}`] = a[0]; }
for (const l of fs.readFileSync(dir + '/industry_category_map.tsv', 'utf8').split('\n').slice(1)) { const a = l.split('\t'); if (a[0]) nameToCat[a[0]] = a[2] || ''; }
const t = {};
const add = (k, n) => (t[k] = (t[k] || 0) + n);
const wrong = {};
for (const l of fs.readFileSync(dir + '/match_result.tsv', 'utf8').split('\n')) {
  const [how, cnt, m, seedRaw, key, ind, dc, share] = l.split('\t');
  const n = +cnt;
  if (how === 'none') { add('none', n); continue; }
  const seed = seedRaw.replace('시험/자격증', '시험·자격증');
  const cat = nameToCat[codeOf[ind]];
  const tier = how === 'exact' ? 'exact' : how === 'prefix' ? 'prefix' : 'trunc';
  let r;
  if (!cat) r = 'nohint';
  else if (cat === seed) r = 'sub_ok';
  else if (cat === parent[seed]) r = 'major_ok';
  else if (parent[cat] === parent[seed]) r = 'same_major';
  else { r = 'wrong'; wrong[`${m} → ${cat} (정답 ${seed})`] = n; }
  add(tier + ':' + r, n); add('all:' + r, n);
}
console.log(Object.entries(t).sort().map(([k, v]) => `${k}\t${v}`).join('\n'));
console.log('--- 오답 상위');
console.log(Object.entries(wrong).sort((a, b) => b[1] - a[1]).slice(0, 25).map(([k, v]) => `${v}\t${k}`).join('\n'));
