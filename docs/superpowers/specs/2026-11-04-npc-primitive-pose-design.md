# Primitive display pose arithmetic

The next N12 candidate removes boxed numeric intermediates from the existing
DisplayTransform quaternion conversion and reciprocal scale calculation.
The connected baseline profile samples both decomposition and metadata application;
this is a bounded allocation hypothesis, not a promised server CPU improvement.

Alternatives considered: spatial viewer indexing requires a separate lifecycle
design, and composite geometry requires pack-format and native visual acceptance.
This smaller arithmetic change is the implementation choice under the user's
standing instruction to work through sensible performance improvements.

Keep all branch predicates, double arithmetic order, float conversion points and
metadata ownership exactly unchanged. Return fresh rotation arrays. Replace the
temporary Array<Double> with primitive local components and the reciprocal
List<Double> with a DoubleArray initializer. No scratch cache, algorithm shortcut,
dependency, geometry, interpolation, range or collision change. Resourcepacks #62
must not enter the build: use accepted resourcepacks main eab25db.

Acceptance: a warmed owned-JVM allocation probe must demonstrate reduced conversion
allocations, all four quaternion branches must preserve output float bits, all
licensed Host tests and the 723456-state golden must pass. Compare frozen native
Stage jars with 20/60 real offline clients, motion and readmission. Keep adverse
windows. A component-only gain is insufficient if the server comparison regresses.
Do not deploy or merge an unqualified candidate. No Production or release work.
