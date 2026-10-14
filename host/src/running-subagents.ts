/** Narrow read-only shapes inspected in rc.2 AgentRegistry/SessionStore.
 * origin is navigation classification; parentSession alone also links ordinary
 * independent forks, so only origin:subagent edges contribute delegated work.
 */
export interface LiveSession {
  readonly id: string;
  readonly header: { readonly id: string; readonly createdAt: number; readonly parentSession?: string; readonly origin?: string; readonly isSeeded?: boolean };
}
export interface LiveAgent { readonly id: string; status: string; readonly session: LiveSession }

/** Pure live-state reduction: no query/projection/log read, no Agent admission.
 * Counts running descendants, not idle resident children or the parent itself.
 * Retained live sessions supply an idle intermediate's header. Missing ancestors
 * stop the walk; duplicate agents and damaged lineage cycles never double count.
 */
export function runningSubagentCounts(agents: readonly LiveAgent[], sessions: readonly LiveSession[]): ReadonlyMap<string, number> {
  const headers = new Map(sessions.map(session => [session.id, session.header]));
  for (const agent of agents) headers.set(agent.id, agent.session.header);
  const counts = new Map<string, number>(), counted = new Set<string>();
  for (const agent of agents) {
    if (agent.status !== 'running' || counted.has(agent.id)) continue;
    counted.add(agent.id);
    const seen = new Set([agent.id]);
    let header = agent.session.header;
    while (header.origin === 'subagent' && typeof header.parentSession === 'string' && !seen.has(header.parentSession)) {
      const parent = header.parentSession; seen.add(parent);
      counts.set(parent, (counts.get(parent) ?? 0) + 1);
      const next = headers.get(parent);
      if (!next) break;
      header = next;
    }
  }
  return counts;
}
