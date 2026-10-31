import { ruleName } from '../format';

/** Which rule raised an alert. The explanation sits beside it, in the rule's own words. */
export function RuleBadge({ detector }: { detector: string }) {
  return <span className="rule">{ruleName(detector)}</span>;
}
