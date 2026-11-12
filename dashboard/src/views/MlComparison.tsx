import { count, money, percent, simulatedDate } from '../format';
import { headline, kindName, mlResults, MODEL_ORDER, type ModelKey } from '../mlComparison';

const results = mlResults;
const models = MODEL_ORDER.map((key) => ({ key, ...results.models[key] }));
const test = results.datasets.test;

type Group = { count: number; by_kind: Record<string, number>; examples: Example[] };
type Example = (typeof results.disagreements.both_missed.examples)[number];

/**
 * The offline experiment from analysis/: the six rules against two models trained on the simulator's labeled data.
 * Everything shown is read from the saved results file. None of it is a live prediction.
 */
export function MlComparison() {
  const d = results.disagreements;
  const disagreementModel = results.models[d.model as ModelKey].name.toLowerCase();
  return (
    <>
      <section className="panel" aria-labelledby="ml-title">
        <div className="panel-head">
          <h2 id="ml-title">Rules compared with machine learning</h2>
          <p className="panel-note">
            An offline experiment, not live predictions. Two models were trained in Python on one simulated year of
            labeled transactions (seed {results.seeds.train_and_validation}) and scored once on a separate simulated
            year with different people (seed {results.seeds.test}). The rules' results are the alerts the Java rule
            engine raised on those same transactions as they came through Kafka. No model runs in the pipeline.
          </p>
        </div>
        <p className="scenario-verdict" data-testid="ml-headline">
          {headline(results)}
        </p>
        <p className="panel-note">
          The held-out run has {count(test.transactions)} transactions on {count(test.cards)} cards.{' '}
          {count(test.issue_transactions)} of them ({(test.issue_share * 100).toFixed(1)}%) belong to{' '}
          {count(test.incidents)} planted incidents; the other {count(test.legitimate_transactions)} are legitimate,
          including planted look-alikes. With so few positives, accuracy would be about 97% for a detector that never
          flags anything, so it isn't shown.
        </p>
      </section>

      <section className="panel" aria-labelledby="ml-scores">
        <div className="panel-head">
          <h2 id="ml-scores">Scores on the held-out run</h2>
          <p className="panel-note">
            <strong>Incident recall:</strong> of the planted incidents, the share with at least one flagged
            transaction, which is how the watchdog has always scored its rules. <strong>Precision:</strong> incidents
            caught, out of incidents caught plus legitimate transactions flagged, so a caught incident counts once
            however many of its transactions were flagged. <strong>F1</strong> combines those two. The
            per-transaction columns count every labeled transaction separately: they understate the rules, which
            alert once per incident by design, and flatter a model that flags several charges of one incident.
            PR-AUC summarizes a model's per-transaction scores across every possible threshold.
          </p>
        </div>
        <div className="table-scroll">
          <table data-testid="ml-scores-table">
            <thead>
              <tr>
                <th scope="col">Approach</th>
                <th scope="col" className="number">Precision</th>
                <th scope="col" className="number">Incident recall</th>
                <th scope="col" className="number">F1</th>
                <th scope="col" className="number">Per-transaction precision</th>
                <th scope="col" className="number">Per-transaction recall</th>
                <th scope="col" className="number">PR-AUC</th>
              </tr>
            </thead>
            <tbody>
              {models.map((model) => (
                <tr key={model.key}>
                  <th scope="row">{model.name}</th>
                  <td className="number">{percent(model.test.incident.precision)}</td>
                  <td className="number">
                    {percent(model.test.incident.recall)}
                    <span className="cell-note">
                      {' '}
                      {count(model.test.incident.caught)} of {count(model.test.incident.incidents)}
                    </span>
                  </td>
                  <td className="number">{percent(model.test.incident.f1)}</td>
                  <td className="number">{percent(model.test.transaction.precision)}</td>
                  <td className="number">{percent(model.test.transaction.recall)}</td>
                  <td className="number">{'pr_auc_test' in model ? model.pr_auc_test.toFixed(2) : 'n/a'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        <p className="panel-note">
          Each model's threshold was chosen on validation cards from the training run and then frozen. "Fewer
          simulator tells" is the forest retrained without {results.ablation_removed_features.join(', ')}: features
          that could let it recognize the simulator instead of the behavior.
        </p>
      </section>

      <section className="panel" aria-labelledby="ml-confusion">
        <div className="panel-head">
          <h2 id="ml-confusion">Confusion matrices</h2>
          <p className="panel-note">Every held-out transaction, by what it was and whether it was flagged.</p>
        </div>
        <div className="table-scroll">
          <table>
            <thead>
              <tr>
                <th scope="col">Approach</th>
                <th scope="col" className="number">Issue, flagged</th>
                <th scope="col" className="number">Issue, not flagged</th>
                <th scope="col" className="number">Legitimate, flagged</th>
                <th scope="col" className="number">Legitimate, not flagged</th>
              </tr>
            </thead>
            <tbody>
              {models.map((model) => (
                <tr key={model.key}>
                  <th scope="row">{model.name}</th>
                  <td className="number">{count(model.test.confusion.tp)}</td>
                  <td className="number">{count(model.test.confusion.fn)}</td>
                  <td className="number">{count(model.test.confusion.fp)}</td>
                  <td className="number">{count(model.test.confusion.tn)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </section>

      <section className="panel" aria-labelledby="ml-by-type">
        <div className="panel-head">
          <h2 id="ml-by-type">Incidents caught, by kind</h2>
        </div>
        <Breakdown
          firstColumn="Planted problem"
          kinds={Object.keys(results.models.rules.test.recall_by_issue_type)}
          cell={(key, kind) => {
            const entry = (results.models[key].test.recall_by_issue_type as Record<string, { incidents: number; caught: number; recall: number }>)[kind];
            return `${percent(entry.recall)} (${count(entry.caught)} of ${count(entry.incidents)})`;
          }}
        />
        <div className="panel-head">
          <h2>Legitimate transactions flagged, by kind</h2>
          <p className="panel-note">
            The simulator plants look-alikes so nothing can score perfectly: a second coffee for a friend, tolls
            before a hotel, an app purchase after a $0.99 one. "Ordinary" is everything else.
          </p>
        </div>
        <Breakdown
          firstColumn="Legitimate behavior"
          kinds={Object.keys(results.models.rules.test.false_positives_by_kind)}
          cell={(key, kind) => {
            const entry = (results.models[key].test.false_positives_by_kind as Record<string, { transactions: number; flagged: number }>)[kind];
            return `${count(entry.flagged)} of ${count(entry.transactions)}`;
          }}
        />
      </section>

      <section className="panel" aria-labelledby="ml-disagree">
        <div className="panel-head">
          <h2 id="ml-disagree">Where the rules and the {disagreementModel} disagree</h2>
          <p className="panel-note">
            On the held-out run. Incidents are counted once each; legitimate transactions one by one. The examples
            are real rows from that run, with the model's score from 0 to 1.
          </p>
        </div>
        <Disagreement title="Incidents only the rules caught" group={d.rules_right_model_wrong.incidents_only_rules_caught} />
        <Disagreement title="Legitimate transactions only the model flagged" group={d.rules_right_model_wrong.legit_only_model_flagged} />
        <Disagreement title="Incidents only the model caught" group={d.model_right_rules_wrong.incidents_only_model_caught} />
        <Disagreement title="Legitimate transactions only the rules flagged" group={d.model_right_rules_wrong.legit_only_rules_flagged} />
        <Disagreement title="Incidents both missed" group={d.both_missed} />
        <Disagreement title="Legitimate transactions both flagged" group={d.both_flagged_legit} />
      </section>

      <section className="panel" aria-labelledby="ml-meaning">
        <div className="panel-head">
          <h2 id="ml-meaning">What this does and doesn't show</h2>
        </div>
        <ul className="plain-list">
          <li>
            The rules lose most of their precision on the planted look-alikes, which they can't tell apart by design,
            and miss spending spikes their fixed three-standard-deviation line doesn't reach. The forest sees the same
            history and learns where to draw each line from labeled examples. Logistic regression doesn't manage
            that: conditions like "the same amount again" aren't something a straight line can express, and it
            catches fewer incidents than the rules do. An exact rule is still hard to beat at the exact thing it
            checks, as the duplicate-charge row shows.
          </li>
          <li>
            The data is simulated. The models were trained on the output of the same generator they were tested on,
            so part of what they learned is the simulator's own habits: a duplicate posts 5 to 90 seconds later and a
            second coffee 1 to 5 minutes later, every time. Real transactions aren't that tidy, real labels arrive
            late and incomplete, and real fraud changes to avoid detection. These scores are not real-world fraud
            detection performance.
          </li>
          <li>
            The rules' thresholds are untuned starting values, while each model's threshold was tuned on labeled
            data. A rule also says why it fired in a sentence; a forest gives a score.
          </li>
          <li>
            The model's features were chosen knowing what the six planted problems look like. A kind of problem
            nobody planted would have no labels to learn from, and a rule can be written for it the same day.
          </li>
        </ul>
      </section>
    </>
  );
}

function Breakdown({ firstColumn, kinds, cell }: {
  firstColumn: string;
  kinds: string[];
  cell: (model: ModelKey, kind: string) => string;
}) {
  return (
    <div className="table-scroll">
      <table>
        <thead>
          <tr>
            <th scope="col">{firstColumn}</th>
            {models.map((model) => (
              <th key={model.key} scope="col" className="number">
                {model.name}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {kinds.map((kind) => (
            <tr key={kind}>
              <th scope="row">{kindName(kind)}</th>
              {models.map((model) => (
                <td key={model.key} className="number">
                  {cell(model.key, kind)}
                </td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function Disagreement({ title, group }: { title: string; group: Group }) {
  const kinds = Object.entries(group.by_kind).map(([kind, n]) => `${kindName(kind).toLowerCase()} ${count(n)}`);
  return (
    <details className="table-view">
      <summary>
        {title}: {count(group.count)}
      </summary>
      {kinds.length > 0 && <p className="panel-note">By kind: {kinds.join(', ')}.</p>}
      {group.examples.length > 0 && (
        <div className="table-scroll">
          <table>
            <thead>
              <tr>
                <th scope="col">Day (simulated)</th>
                <th scope="col">Merchant</th>
                <th scope="col" className="number">Amount</th>
                <th scope="col">What it really was</th>
                <th scope="col">Rules alerted</th>
                <th scope="col" className="number">Model score</th>
              </tr>
            </thead>
            <tbody>
              {group.examples.map((example, i) => (
                <tr key={i}>
                  <td className="nowrap">{simulatedDate(example.occurredAt)}</td>
                  <td>
                    {example.merchant}
                    {example.country !== 'US' && <span className="cell-note"> {example.country}</span>}
                  </td>
                  <td className="number">{money(example.amountCents)}</td>
                  <td>{kindName(example.truth)}</td>
                  <td>{example.ruleAlerts ? example.ruleAlerts.replaceAll('|', ', ') : <span className="cell-note">None</span>}</td>
                  <td className="number">{example.modelScore.toFixed(2)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </details>
  );
}
