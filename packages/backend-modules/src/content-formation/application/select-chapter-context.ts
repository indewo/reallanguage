export type ChapterContextUnit = Readonly<{
  unitId: string;
  order: number;
  textZh: string;
}>;
export type ChapterContextCommand = Readonly<{
  accountId: string;
  units: readonly ChapterContextUnit[];
  selectedUnitIds: readonly string[];
  commandId: string;
}>;
export type ChapterContextCandidate = Readonly<{
  chapterId: string;
  unitIds: readonly string[];
}>;
export interface ChapterContextProvider {
  select(
    command: ChapterContextCommand,
  ): Promise<readonly ChapterContextCandidate[]>;
}

export class SelectChapterContext {
  public constructor(private readonly provider: ChapterContextProvider) {}
  public async execute(command: ChapterContextCommand) {
    validateInput(command);
    let suggested: readonly ChapterContextCandidate[];
    try {
      suggested = await this.provider.select(command);
    } catch {
      // Chapter boundaries are fully recoverable from the ordered source units
      // and confirmed focus selection. A provider outage must not strand the user
      // after their choices have already been persisted.
      return { chapters: rebuild(command) };
    }
    return {
      chapters: validSuggestion(command, suggested)
        ? suggested
        : rebuild(command),
    };
  }
}

const validateInput = (command: ChapterContextCommand) => {
  if (
    command.selectedUnitIds.length < 1 ||
    command.selectedUnitIds.length > 3 ||
    new Set(command.selectedUnitIds).size !== command.selectedUnitIds.length
  )
    throw new Error("Focus selection must contain one to three unique units");
  const ordered = [...command.units].sort((a, b) => a.order - b.order);
  if (
    ordered.some((unit, index) => unit.order !== index) ||
    command.selectedUnitIds.some(
      (id) => !ordered.some((unit) => unit.unitId === id),
    )
  )
    throw new Error("Invalid chapter source units");
};

const validSuggestion = (
  command: ChapterContextCommand,
  chapters: readonly ChapterContextCandidate[],
): boolean => {
  if (
    chapters.length === 0 ||
    chapters.some(
      (chapter) =>
        !chapter.chapterId ||
        chapter.unitIds.length < 1 ||
        chapter.unitIds.length > 4,
    )
  )
    return false;
  const positions = new Map(
    command.units.map((unit) => [unit.unitId, unit.order]),
  );
  const focuses = chapters.flatMap((chapter) =>
    chapter.unitIds.filter((id) => command.selectedUnitIds.includes(id)),
  );
  if (
    focuses.length !== command.selectedUnitIds.length ||
    new Set(focuses).size !== focuses.length
  )
    return false;
  const ordered = [...command.units].sort((a, b) => a.order - b.order);
  // A short source is already a bounded, coherent chapter.  Accepting a
  // provider suggestion that drops its first or last sentence loses confirmed
  // user content before English generation, even though the UI and generator
  // behave correctly.  Keep all 1–4 units exactly once and in source order.
  if (
    ordered.length <= 4 &&
    (chapters.length !== 1 ||
      chapters[0]!.unitIds.length !== ordered.length ||
      chapters[0]!.unitIds.some(
        (unitId, index) => unitId !== ordered[index]!.unitId,
      ))
  )
    return false;
  return chapters.every(
    (chapter) =>
      chapter.unitIds.every((id) => positions.has(id)) &&
      chapter.unitIds.every(
        (id, index) =>
          index === 0 ||
          positions.get(chapter.unitIds[index - 1]!)! < positions.get(id)!,
      ),
  );
};

const rebuild = (
  command: ChapterContextCommand,
): readonly ChapterContextCandidate[] => {
  const ordered = [...command.units].sort((a, b) => a.order - b.order);
  if (ordered.length <= 4) {
    return [
      {
        chapterId: "chapter-1",
        unitIds: ordered.map((unit) => unit.unitId),
      },
    ];
  }
  const focusIndexes = command.selectedUnitIds
    .map((id) => ordered.findIndex((unit) => unit.unitId === id))
    .sort((a, b) => a - b);
  const groups: number[][] = [];
  for (const focus of focusIndexes) {
    const last = groups.at(-1);
    if (last && focus - last[0]! <= 3) last.push(focus);
    else groups.push([focus]);
  }
  return groups.map((group, index) => {
    const first = group[0]!;
    const last = group.at(-1)!;
    let start = Math.max(0, first - 1);
    const end = Math.min(ordered.length - 1, Math.max(last, start + 2));
    if (end - start + 1 > 4) start = last - 3;
    return {
      chapterId: `chapter-${index + 1}`,
      unitIds: ordered.slice(start, end + 1).map((unit) => unit.unitId),
    };
  });
};
