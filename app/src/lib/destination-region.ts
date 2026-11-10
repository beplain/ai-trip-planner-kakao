export const DESTINATION_REGIONS: { name: string; aliases: string[] }[] = [
  { name: '서울특별시', aliases: ['서울'] }, { name: '부산광역시', aliases: ['부산'] },
  { name: '대구광역시', aliases: ['대구'] }, { name: '인천광역시', aliases: ['인천'] },
  { name: '대전광역시', aliases: ['대전'] },
  { name: '울산광역시', aliases: ['울산'] }, { name: '세종특별자치시', aliases: ['세종'] },
  { name: '경기도', aliases: ['경기'] }, { name: '강원특별자치도', aliases: ['강원도', '강원'] },
  { name: '충청북도', aliases: ['충북'] }, { name: '충청남도', aliases: ['충남'] },
  { name: '전북특별자치도', aliases: ['전라북도', '전북'] }, { name: '전남광주통합특별시', aliases: ['전남광주', '광주광역시', '전라남도', '전남'] },
  { name: '경상북도', aliases: ['경북'] }, { name: '경상남도', aliases: ['경남'] },
  { name: '제주특별자치도', aliases: ['제주도', '제주'] },
];

const subdivisions: Record<string, string> = {
  경기도: '수원시 성남시 의정부시 안양시 부천시 광명시 평택시 동두천시 안산시 고양시 과천시 구리시 남양주시 오산시 시흥시 군포시 의왕시 하남시 용인시 파주시 이천시 안성시 김포시 화성시 광주시 양주시 포천시 여주시 연천군 가평군 양평군',
  강원특별자치도: '춘천시 원주시 강릉시 동해시 태백시 속초시 삼척시 홍천군 횡성군 영월군 평창군 정선군 철원군 화천군 양구군 인제군 고성군 양양군',
  충청북도: '청주시 충주시 제천시 보은군 옥천군 영동군 증평군 진천군 괴산군 음성군 단양군',
  충청남도: '천안시 공주시 보령시 아산시 서산시 논산시 계룡시 당진시 금산군 부여군 서천군 청양군 홍성군 예산군 태안군',
  전북특별자치도: '전주시 군산시 익산시 정읍시 남원시 김제시 완주군 진안군 무주군 장수군 임실군 순창군 고창군 부안군',
  전남광주통합특별시: '목포시 여수시 순천시 나주시 광양시 담양군 곡성군 구례군 고흥군 보성군 화순군 장흥군 강진군 해남군 영암군 무안군 함평군 영광군 장성군 완도군 진도군 신안군',
  경상북도: '포항시 경주시 김천시 안동시 구미시 영주시 영천시 상주시 문경시 경산시 의성군 청송군 영양군 영덕군 청도군 고령군 성주군 칠곡군 예천군 봉화군 울진군 울릉군',
  경상남도: '창원시 진주시 통영시 사천시 김해시 밀양시 거제시 양산시 의령군 함안군 창녕군 고성군 남해군 하동군 산청군 함양군 거창군 합천군',
  제주특별자치도: '제주시 서귀포시',
  서울특별시: '종로구 중구 용산구 성동구 광진구 동대문구 중랑구 성북구 강북구 도봉구 노원구 은평구 서대문구 마포구 양천구 강서구 구로구 금천구 영등포구 동작구 관악구 서초구 강남구 송파구 강동구',
  부산광역시: '중구 서구 동구 영도구 부산진구 동래구 남구 북구 해운대구 사하구 금정구 강서구 연제구 수영구 사상구 기장군',
  대구광역시: '중구 동구 서구 남구 북구 수성구 달서구 달성군 군위군',
  인천광역시: '중구 동구 미추홀구 연수구 남동구 부평구 계양구 서구 강화군 옹진군',
  대전광역시: '동구 중구 서구 유성구 대덕구',
  울산광역시: '중구 남구 동구 북구 울주군',
};
const regions = [...DESTINATION_REGIONS, ...Object.entries(subdivisions).flatMap(([parent, children]) => children.split(' ').map(child => ({ name: `${parent} ${child}`, aliases: [child, child.slice(0, -1)] })))];
const compact = (value: string) => value.trim().replace(/^(대한민국|한국)\s*/, '').replace(/\s+/g, '');
export function destinationCandidates(value: string): string[] {
  const text = compact(value);
  if (!text) return DESTINATION_REGIONS.map(r => r.name);
  const exact = regions.filter(r => [r.name, ...r.aliases].some(a => compact(a) === text));
  if (text === '광주') return ['전남광주통합특별시', '경기도 광주시'];
  if (exact.length) return exact.map(r => r.name);
  const normalized = normalizeAddress(value);
  const full = regions.filter(r => compact(r.name) === compact(normalized));
  if (full.length) return full.map(r => r.name);
  return regions.filter(r => [r.name, ...r.aliases].some(a => compact(a).includes(text))).map(r => r.name);
}
function normalizeAddress(value: string): string {
  const text = value.trim().replace(/^(대한민국|한국)\s*/, '');
  for (const region of DESTINATION_REGIONS) for (const alias of [region.name, ...region.aliases].sort((a,b) => b.length-a.length)) {
    if (text === alias || text.startsWith(alias + ' ')) return region.name + text.slice(alias.length);
  }
  return text;
}
export function destinationRegion(value: string): string | undefined {
  const matches = destinationCandidates(value);
  const normalized = normalizeAddress(value);
  const exact = matches.find(name => compact(name) === compact(normalized));
  if (exact) return exact;
  return matches.length === 1 ? matches[0] : undefined;
}
/** Map selection keeps cities/counties, discarding boroughs and neighborhoods. */
export function mapDestinationRegion(value: string): string | undefined {
  const normalized = normalizeAddress(value);
  const parent = DESTINATION_REGIONS.find(r => normalized === r.name || normalized.startsWith(r.name + ' '));
  if (!parent) return undefined;
  const child = normalized.slice(parent.name.length).trim().split(' ')[0];
  return child && /[시군]$/.test(child) ? destinationRegion(`${parent.name} ${child}`) : parent.name;
}
