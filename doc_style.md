# Documentation Writing Style

Documentation must let a reader understand an API or process without borrowing context from the
source code, another page, or the author's current task.

## Establish context first

Start by naming the subject and its role. Then describe its inputs, behavior, and result. A reader
should not have to infer what a noun refers to.

Avoid undefined labels such as "factory", "category", "record", or "bound" when several meanings
are possible. Use the concrete term instead. If a specialized term is necessary, define it at first
use in parentheses.

Bad:

> All factories accept scalar-declaration bounds and categories and preserve input order.

Better:

> `createAccounts` creates one account for each supplied identifier. Every account uses the
> requested credit limit and account type. Returned accounts follow identifier input order.

## Describe the contract, not the code tour

Prioritize facts that affect a caller:

- what the operation represents or does;
- accepted inputs, units, ranges, and defaults;
- ordering, identity, partitioning, and ownership rules;
- lazy versus immediate work;
- returned data and when it is available;
- errors or unsupported cases that are part of the contract.

Mention implementation details only when they explain observable behavior, performance, resource
ownership, numerical meaning, or a limitation. Do not narrate method bodies.

Be explicit about absence. State what `None`, an empty collection, `NaN`, or infinity means instead
of leaving the reader to guess.

## Keep terms local and exact

Use one term for one concept. Prefer API names and mathematical expressions over loose synonyms.
Define symbols where they appear; for example, say that `A` is the constraint matrix before using
`A^T x`.

References add context but do not replace it. A sentence must remain understandable before the
reader follows a link. Link to the authoritative definition rather than repeating it, and avoid
ambiguous links to overloaded methods.

## Keep prose focused

Use short paragraphs and direct sentences. Each paragraph should answer one question. Use a table
for repeated field mappings or contracts, and a numbered list for an ordered process. Do not expand
a simple API fact into a tutorial unless the surrounding document is a tutorial.

Examples must use terms introduced by the surrounding text and must demonstrate the documented
contract rather than an unrelated feature.

## ScalaDoc rules

Every ScalaDoc block starts with a direct description of the documented type or member. Add
narrative only when the summary cannot express the contract safely.

Every case class has ScalaDoc with:

- a description of what one instance represents;
- one `@param` entry for every constructor field, including private and internal case classes;
- units, absence semantics, ordering, identity, or allowed values where relevant.

For methods, document parameters whose meaning is not evident from the signature. Add `@return`
when the result needs interpretation and `@throws` only for stable caller-facing failure conditions.

Do not restate the type. For example, `@param timeout timeout duration` is not useful; specify when
the timer starts, what work it covers, and what absence means.

## Review checklist

- The first sentence identifies the subject and its role.
- Every term is defined locally or linked after a local explanation.
- The text describes observable behavior, not an implementation walk-through.
- Units, defaults, limits, ownership, and absence semantics are explicit where relevant.
- Every case-class field has an exact `@param` description.
- References resolve and add useful detail.
- The documentation builds without detached-comment or broken-link warnings.
