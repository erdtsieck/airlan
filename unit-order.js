/**
 * `units` in the order of `ids`: ids that name no unit are ignored, and units that `ids`
 * leaves out keep their relative order after the named ones, so a client that missed a
 * newly found unit cannot lose it by reordering.
 */
export function reorder(units, ids) {
  const byId = new Map(units.map((u) => [u.airconId, u]));
  const named = [...new Set(ids)].filter((id) => byId.has(id)).map((id) => byId.get(id));
  return named.concat(units.filter((u) => !named.includes(u)));
}
