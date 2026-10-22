import { StyleSheet } from 'react-native';

// 기존 프로토타입 디자인(파란 톤 카드 UI)을 그대로 유지한 공용 스타일
export const C = {
  bg: '#F6F7FB',
  primary: '#4169E1',
  primaryDark: '#2447C6',
  text: '#18223B',
  sub: '#737D92',
  border: '#E9ECF3',
  danger: '#C8525C',
  warnBg: '#FFF7E6',
  warnText: '#8A5A00',
  ok: '#1E8E5A',
};

export const DAY_COLORS = ['#4169E1', '#E8743B', '#19A979', '#945ECF', '#ED4A7B', '#13A4B4', '#BF8B00'];

export const s = StyleSheet.create({
  safe: { flex: 1, backgroundColor: C.bg }, content: { padding: 20, paddingBottom: 36 },
  header: { height: 56, paddingHorizontal: 18, flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', backgroundColor: C.bg },
  back: { width: 42, height: 42, borderRadius: 14, backgroundColor: '#fff', alignItems: 'center', justifyContent: 'center' }, backText: { fontSize: 32, color: C.text, marginTop: -5 },
  logo: { width: 38, height: 38, borderRadius: 13, backgroundColor: C.primary, alignItems: 'center', justifyContent: 'center' }, logoText: { color: '#fff', fontWeight: '900', fontSize: 20 }, headerTitle: { fontSize: 17, fontWeight: '800', color: C.text },
  welcome: { backgroundColor: C.primaryDark, borderRadius: 26, padding: 24, marginTop: 12, marginBottom: 18 }, eyebrowLight: { color: '#C9D5FF', fontSize: 12, fontWeight: '700', marginBottom: 10 }, welcomeTitle: { color: '#fff', fontSize: 30, lineHeight: 38, fontWeight: '900' }, welcomeSub: { color: '#DCE4FF', fontSize: 14, lineHeight: 21, marginTop: 10 },
  eyebrow: { color: C.primary, fontSize: 13, fontWeight: '800', marginTop: 10, marginBottom: 8 }, title: { color: C.text, fontSize: 27, fontWeight: '900', lineHeight: 34 }, subtitle: { color: C.sub, fontSize: 14, lineHeight: 21, marginTop: 8, marginBottom: 24 },
  primary: { backgroundColor: C.primary, minHeight: 54, paddingHorizontal: 18, borderRadius: 16, alignItems: 'center', justifyContent: 'center', marginBottom: 22, elevation: 2 }, primaryText: { color: '#fff', fontSize: 16, fontWeight: '800' },
  disabled: { opacity: 0.55 },
  sectionHead: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', marginTop: 20, marginBottom: 12 }, sectionTitle: { color: C.text, fontSize: 19, fontWeight: '900' }, sectionHint: { color: '#8790A2', fontSize: 12, fontWeight: '600' },
  search: { backgroundColor: '#fff', borderRadius: 14, borderWidth: 1, borderColor: C.border, paddingHorizontal: 16, height: 48, fontSize: 14, marginBottom: 12 },
  tripCard: { flexDirection: 'row', alignItems: 'center', backgroundColor: '#fff', borderRadius: 18, padding: 15, marginBottom: 11, borderWidth: 1, borderColor: '#EEF0F5' }, tripIcon: { width: 48, height: 48, borderRadius: 16, backgroundColor: '#EEF2FF', alignItems: 'center', justifyContent: 'center', marginRight: 13 }, tripEmoji: { color: C.primary, fontSize: 23 }, tripInfo: { flex: 1 }, tripTitle: { color: C.text, fontSize: 16, fontWeight: '800' }, tripMeta: { color: '#6F7A90', fontSize: 12, marginTop: 5 }, tripStyles: { color: C.primary, fontSize: 11, fontWeight: '700', marginTop: 6 }, chevron: { color: '#AAB1C0', fontSize: 27, marginLeft: 8 },
  emptyCard: { alignItems: 'center', backgroundColor: '#fff', padding: 28, borderRadius: 18 }, emptyTitle: { fontSize: 16, fontWeight: '800', color: C.text, marginBottom: 7 }, empty: { color: '#7B8496', fontSize: 13, lineHeight: 20 }, footerNote: { paddingVertical: 18, alignItems: 'center' }, footerText: { color: '#A2A9B7', fontSize: 11, textAlign: 'center' },
  tabBar: { height: 66, flexDirection: 'row', backgroundColor: '#fff', borderTopWidth: 1, borderTopColor: '#EAECF2', justifyContent: 'space-around', alignItems: 'center' }, tab: { alignItems: 'center', width: 90 }, tabGlyph: { color: C.primary, fontSize: 20, fontWeight: '800' }, tabGlyphOff: { color: '#9299A8', fontSize: 20, fontWeight: '800' }, tabText: { color: '#9299A8', fontSize: 11 }, tabActiveText: { color: C.primary, fontSize: 11, fontWeight: '800' },
  label: { color: '#303A50', fontSize: 14, fontWeight: '800', marginBottom: 8, marginTop: 16 }, required: { color: '#E35D6A' }, input: { backgroundColor: '#fff', height: 52, paddingHorizontal: 14, borderWidth: 1, borderColor: '#E3E6EF', borderRadius: 14, color: C.text, fontSize: 15 }, inputMulti: { height: 84, paddingTop: 12, textAlignVertical: 'top' }, row: { flexDirection: 'row', gap: 12 }, chips: { flexDirection: 'row', flexWrap: 'wrap', gap: 8, marginTop: 3 }, chip: { paddingHorizontal: 15, paddingVertical: 10, borderRadius: 20, backgroundColor: '#fff', borderWidth: 1, borderColor: '#E2E6EF' }, chipActive: { backgroundColor: '#EAF0FF', borderColor: C.primary }, chipText: { color: '#657087', fontSize: 13, fontWeight: '700' }, chipTextActive: { color: '#3155CC' },
  infoBox: { padding: 16, backgroundColor: '#EDF2FF', borderRadius: 15, marginTop: 22, marginBottom: 20 }, infoTitle: { color: '#2746A5', fontWeight: '800', fontSize: 13, marginBottom: 6 }, infoText: { color: '#5F6D8B', fontSize: 12, lineHeight: 19 },
  warnBox: { padding: 14, backgroundColor: C.warnBg, borderRadius: 15, marginTop: 14 }, warnTitle: { color: C.warnText, fontWeight: '800', fontSize: 13, marginBottom: 6 }, warnText: { color: C.warnText, fontSize: 12, lineHeight: 18 },
  detailHero: { backgroundColor: C.primaryDark, padding: 22, borderRadius: 23, marginTop: 10 }, detailTitle: { color: '#fff', fontWeight: '900', fontSize: 25, marginTop: 3 }, detailMeta: { color: '#DCE4FF', fontSize: 12, marginTop: 8 },
  actionRow: { flexDirection: 'row', gap: 9, marginVertical: 16 }, actionButton: { flex: 1, alignItems: 'center', justifyContent: 'center', backgroundColor: '#fff', borderRadius: 15, paddingVertical: 13, borderWidth: 1, borderColor: '#EEF0F5' }, actionIcon: { color: C.primary, fontSize: 20, fontWeight: '800', marginBottom: 4 }, actionText: { color: '#34405A', fontSize: 11, fontWeight: '700' },
  dayLabel: { color: C.primary, fontSize: 13, fontWeight: '900', marginTop: 14, marginBottom: 13 },
  placeCard: { flexDirection: 'row' }, timeline: { width: 22, alignItems: 'center' }, dot: { width: 12, height: 12, borderRadius: 6, backgroundColor: C.primary, marginTop: 5 }, line: { width: 2, backgroundColor: '#D8E0FA', flex: 1, marginTop: 4 },
  placeBody: { flex: 1, backgroundColor: '#fff', borderRadius: 16, padding: 14, marginBottom: 12, borderWidth: 1, borderColor: '#EDF0F5' }, placeTime: { color: C.primary, fontWeight: '800', fontSize: 11 }, placeName: { color: C.text, fontWeight: '900', fontSize: 16, marginTop: 5 }, placeAddr: { color: '#8A93A6', fontSize: 11, marginTop: 3 }, placeNote: { color: '#7B8496', fontSize: 12, lineHeight: 18, marginTop: 5 },
  placeActions: { flexDirection: 'row', flexWrap: 'wrap', gap: 7, marginTop: 10 }, smallButton: { paddingHorizontal: 9, paddingVertical: 6, backgroundColor: '#F1F4FB', borderRadius: 8 }, smallButtonText: { color: '#52617E', fontSize: 11, fontWeight: '700' }, deleteButton: { paddingHorizontal: 9, paddingVertical: 6, backgroundColor: '#FFF0F0', borderRadius: 8 }, deleteText: { color: C.danger, fontSize: 11, fontWeight: '700' },
  linkRow: { borderTopWidth: 1, borderTopColor: '#F0F1F5', marginTop: 10, paddingTop: 8, flexDirection: 'row', flexWrap: 'wrap', gap: 12 }, linkText: { color: C.primary, fontSize: 12, fontWeight: '700' }, mutedSmall: { color: '#74809A', fontSize: 11, lineHeight: 17 },
  badge: { alignSelf: 'flex-start', paddingHorizontal: 7, paddingVertical: 2, borderRadius: 6, marginTop: 6 }, badgeText: { fontSize: 10, fontWeight: '800' },
  memberBox: { backgroundColor: '#fff', borderRadius: 16, padding: 16, marginTop: 6 }, sectionTitleSmall: { color: C.text, fontWeight: '800', fontSize: 14 }, memberNames: { color: '#68748C', fontSize: 13, marginTop: 8, lineHeight: 20 },
  outlineButton: { minHeight: 48, borderWidth: 1, borderColor: '#DDE2EC', borderRadius: 14, alignItems: 'center', justifyContent: 'center', marginTop: 16, paddingHorizontal: 14 }, outlineText: { color: '#5B6579', fontWeight: '800', fontSize: 14 },
  center: { flex: 1, alignItems: 'center', justifyContent: 'center', padding: 24 },
});
