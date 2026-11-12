import { describe, expect, it } from 'vitest';
import { headline, kindName, mlResults, MODEL_ORDER, type MlResults } from './mlComparison';

function withF1(rules: number, logistic: number, forest: number): MlResults {
  const copy = structuredClone(mlResults);
  copy.models.rules.test.incident.f1 = rules;
  copy.models.logistic_regression.test.incident.f1 = logistic;
  copy.models.random_forest.test.incident.f1 = forest;
  return copy;
}

describe('ML comparison', () => {
  it('names the better model when a model beats the rules', () => {
    expect(headline(withF1(0.56, 0.72, 0.95))).toBe(
      'On the held-out run, the random forest scored higher than the rules: F1 95% against 56%.',
    );
  });

  it('says so when the rules do at least as well', () => {
    expect(headline(withF1(0.9, 0.72, 0.85))).toBe(
      'On the held-out run, the rules scored at least as high as either model: F1 90% against 85% for the random forest.',
    );
  });

  it('reads results that were scored on a different run than they were trained on', () => {
    expect(mlResults.seeds.test).not.toBe(mlResults.seeds.train_and_validation);
    for (const key of MODEL_ORDER) {
      const { tp, fp, fn, tn } = mlResults.models[key].test.confusion;
      expect(tp + fp + fn + tn).toBe(mlResults.datasets.test.transactions);
    }
  });

  it('turns a label into words', () => {
    expect(kindName('LEGIT_BIG_PURCHASE')).toBe('Legit big purchase');
  });
});
