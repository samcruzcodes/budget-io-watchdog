// The offline rules-vs-ML experiment (analysis/experiment.py), as saved in ml-results.json. Every number the ML
// comparison tab shows is read from that file; nothing here is typed in by hand.

import saved from './ml-results.json';

export type MlResults = typeof saved;
export type ModelKey = keyof MlResults['models'];
export type ModelResult = MlResults['models'][ModelKey];

export const mlResults: MlResults = saved;

/** The order the tab lists approaches in: the rules first, then the models. */
export const MODEL_ORDER: ModelKey[] = ['rules', 'logistic_regression', 'random_forest', 'random_forest_ablation'];

/** One sentence saying who did best on the held-out run, worked out from the saved numbers. */
export function headline(results: MlResults): string {
  const f1 = (key: ModelKey) => results.models[key].test.incident.f1 ?? 0;
  const models: ModelKey[] = ['logistic_regression', 'random_forest'];
  const best = models.reduce((a, b) => (f1(b) > f1(a) ? b : a));
  const pct = (value: number) => `${Math.round(value * 100)}%`;
  const name = results.models[best].name.toLowerCase();
  if (f1(best) > f1('rules')) {
    return `On the held-out run, the ${name} scored higher than the rules: F1 ${pct(f1(best))} against ${pct(f1('rules'))}.`;
  }
  return `On the held-out run, the rules scored at least as high as either model: F1 ${pct(f1('rules'))} against ${pct(f1(best))} for the ${name}.`;
}

/** "SPENDING_SPIKE" -> "Spending spike". */
export function kindName(kind: string): string {
  const words = kind.toLowerCase().replaceAll('_', ' ');
  return words.charAt(0).toUpperCase() + words.slice(1);
}
