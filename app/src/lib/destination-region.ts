export const DESTINATION_REGIONS: { name: string; aliases: string[] }[] = [
  { name: '서울특별시', aliases: ['서울'] }, { name: '부산광역시', aliases: ['부산'] },
  { name: '대구광역시', aliases: ['대구'] }, { name: '인천광역시', aliases: ['인천'] },
  { name: '광주광역시', aliases: ['광주'] }, { name: '대전광역시', aliases: ['대전'] },
  { name: '울산광역시', aliases: ['울산'] }, { name: '세종특별자치시', aliases: ['세종'] },
  { name: '경기도', aliases: ['경기'] }, { name: '강원특별자치도', aliases: ['강원도', '강원'] },
  { name: '충청북도', aliases: ['충북'] }, { name: '충청남도', aliases: ['충남'] },
  { name: '전북특별자치도', aliases: ['전라북도', '전북'] }, { name: '전라남도', aliases: ['전남'] },
  { name: '경상북도', aliases: ['경북'] }, { name: '경상남도', aliases: ['경남'] },
  { name: '제주특별자치도', aliases: ['제주도', '제주'] },
];

export function destinationRegion(value: string): string | undefined {
  const text = value.trim().replace(/^(대한민국|한국)\s*/, '').replace(/\s+/g, '');
  return DESTINATION_REGIONS.find(({ name, aliases }) => [name, ...aliases].some((alias) => text.startsWith(alias)))?.name;
}
