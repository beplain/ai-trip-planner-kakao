export type RowBounds = { y: number; height: number };

/** Cross half of each neighbour's height; a tall card can still pass a short card. */
export function dropIndex(rows: RowBounds[], from: number, delta: number): number {
  if (!rows[from] || rows.some((r) => !r || r.height <= 0)) return from;
  const origin = rows[from];
  let target = from;
  if (delta > 0) {
    for (let i = from + 1; i < rows.length; i++) {
      if (delta > rows[i].y - origin.y - origin.height + rows[i].height / 2) target = i;
    }
  } else {
    for (let i = from - 1; i >= 0; i--) {
      if (delta < rows[i].y + rows[i].height / 2 - origin.y) target = i;
    }
  }
  return target;
}

export function reorderItems<T>(items: T[], from: number, to: number): T[] {
  if (from === to || from < 0 || to < 0 || from >= items.length || to >= items.length) return items;
  const next = [...items]; const [item] = next.splice(from, 1); next.splice(to, 0, item); return next;
}
