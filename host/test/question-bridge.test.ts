import assert from 'node:assert/strict';
import { test } from 'node:test';
import { createQuestionBridge } from '../src/question-bridge.ts';
import { answerSyntax, validateAnswer, questionsOf } from '../src/phone.ts';

const question = questionsOf([{ callId: 'synthetic-call', state: 'open', questions: [{ id: 'q', header: 'Synthetic', question: 'Choose one', options: [{ label: 'A' }, { label: 'B' }] }] }])[0]!;
const answer = { answers: [{ id: 'q', selected: ['A'] }] };

test('question answer validates IDs, labels, single/multiple selection and free text', () => {
  validateAnswer(question, answerSyntax(answer));
  validateAnswer(question, answerSyntax({ answers: [{ id: 'q', selected: [] }] })); // Desktop skip representation.
  validateAnswer(question, answerSyntax({ answers: [{ id: 'q', selected: [], custom: 'Other' }] }));
  for (const invalid of [ { answers: [] }, { answers: [{ id: 'q', selected: ['missing'] }] }, { answers: [{ id: 'q', selected: ['A', 'B'] }] }, { answers: [{ id: 'wrong', selected: ['A'] }] }, { answers: [{ id: 'q', selected: ['A', 'A'] }] }, { answers: [{ id: 'q', selected: [], custom: ' ' }] } ]) assert.throws(() => validateAnswer(question, answerSyntax(invalid)), { code: 'invalid_request' });
  validateAnswer({ ...question, questions: [{ ...question.questions[0]!, multiSelect: true }] }, answerSyntax({ answers: [{ id: 'q', selected: ['A', 'B'], custom: 'Also' }] }));
  assert.throws(() => validateAnswer({ ...question, questions: [{ ...question.questions[0]!, allowFreeText: false }] }, answerSyntax({ answers: [{ id: 'q', selected: [], custom: 'No' }] })), { code: 'invalid_request' });
});

test('open question bridge races desktop, handles no answerer, expires and does not answer twice', async () => {
  let listener: any;
  const bridge = createQuestionBridge(fn => { listener = fn; return () => {}; });
  const abort = new AbortController();
  const request = { agent: { session: { id: 'session' } }, wait: { callId: question.callId }, questions: question.questions, signal: abort.signal };
  const pending = listener(request, () => Promise.reject(Object.assign(new Error('Synthetic no provider'), { code: 'NO_PROVIDER' })));
  assert.equal(bridge.pending('other').length, 0); assert.equal(bridge.pending('session').length, 1);
  assert.equal(bridge.answer('other', question.callId, answer), false);
  assert.equal(bridge.answer('session', question.callId, answer), true);
  assert.equal(bridge.answer('session', question.callId, answer), false);
  assert.deepEqual(await pending, answer);
  const desktop = listener(request, async () => answer); assert.deepEqual(await desktop, answer); assert.equal(bridge.pending('session').length, 0);
  const expired = listener(request, () => new Promise(() => {})); abort.abort(); await assert.rejects(expired, { name: 'AbortError' });
  assert.equal(bridge.answer('session', question.callId, answer), false); bridge.close();
});
