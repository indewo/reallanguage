export type YearMonth = Readonly<{
  year: number;
  month: number;
}>;

const minimumRegistrationAgeInMonths = 14 * 12;

export const isRegistrationAgeEligible = (
  birth: YearMonth,
  current: YearMonth,
): boolean =>
  toMonthIndex(current) - toMonthIndex(birth) >= minimumRegistrationAgeInMonths;

const toMonthIndex = ({ year, month }: YearMonth): number =>
  year * 12 + month - 1;
