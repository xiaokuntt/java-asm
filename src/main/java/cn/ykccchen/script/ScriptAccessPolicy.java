package cn.ykccchen.script;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Controls which Java classes and members may be accessed by a script.
 * The default policy is permissive for backward compatibility.
 */
public interface ScriptAccessPolicy {

	boolean allowClass(String className);

	boolean allowMember(Class<?> owner, String memberName);

	static ScriptAccessPolicy allowAll() {
		return new ScriptAccessPolicy() {
			@Override
			public boolean allowClass(String className) {
				return true;
			}

			@Override
			public boolean allowMember(Class<?> owner, String memberName) {
				return true;
			}
		};
	}

	/** Creates an allow-list policy; class access is denied unless explicitly allowed. */
	static Builder builder() {
		return new Builder();
	}

	/**
	 * Creates a deny-by-default builder with safe language, collection and time
	 * capabilities. Host applications can explicitly add their own classes or
	 * opt into additional capabilities before calling {@link Builder#build()}.
	 */
	static Builder productionBuilder() {
		return builder()
				.allowCapability(Capability.CORE_TYPES)
				.allowCapability(Capability.COLLECTIONS)
				.allowCapability(Capability.TIME)
				.denyMember(Object.class.getName(), "class")
				.denyMember(Object.class.getName(), "getClass")
				.denyMember(Object.class.getName(), "wait")
				.denyMember(Object.class.getName(), "notify")
				.denyMember(Object.class.getName(), "notifyAll")
				.denyMember(Object.class.getName(), "clone")
				.denyMember(Object.class.getName(), "finalize");
	}

	/** Recommended deny-by-default policy for scripts that do not need host I/O. */
	static ScriptAccessPolicy productionDefaults() {
		return productionBuilder().build();
	}

	/** Coarse capabilities that can be granted without maintaining class lists manually. */
	enum Capability {
		CORE_TYPES,
		COLLECTIONS,
		TIME,
		ASYNC_INTEROP,
		FILE_IO,
		NETWORK,
		PROCESS,
		SYSTEM
	}

	final class Builder {
		private final List<String> allowedClassPrefixes = new ArrayList<>();
		private final List<String> deniedClassPrefixes = new ArrayList<>();
		private final List<MemberRule> allowedMembers = new ArrayList<>();
		private final List<MemberRule> deniedMembers = new ArrayList<>();
		private boolean defaultClassAccess;
		private boolean defaultMemberAccess = true;

		public Builder allowClassPrefix(String prefix) {
			allowedClassPrefixes.add(requireRule(prefix, "prefix"));
			return this;
		}

		public Builder denyClassPrefix(String prefix) {
			deniedClassPrefixes.add(requireRule(prefix, "prefix"));
			return this;
		}

		public Builder allowMember(String ownerPrefix, String memberName) {
			allowedMembers.add(new MemberRule(ownerPrefix, memberName));
			return this;
		}

		public Builder denyMember(String ownerPrefix, String memberName) {
			deniedMembers.add(new MemberRule(ownerPrefix, memberName));
			return this;
		}

		public Builder allowCapability(Capability capability) {
			switch (Objects.requireNonNull(capability, "capability")) {
				case CORE_TYPES:
					return allowClassNames(
							"java.lang.String", "java.lang.StringBuilder", "java.lang.StringBuffer",
							"java.lang.Boolean", "java.lang.Byte", "java.lang.Short",
							"java.lang.Integer", "java.lang.Long", "java.lang.Float",
							"java.lang.Double", "java.lang.Character", "java.lang.Number",
							"java.lang.Math", "java.lang.StrictMath", "java.lang.Enum",
							"java.math.BigDecimal", "java.math.BigInteger");
				case COLLECTIONS:
					return allowClassNames(
							"java.lang.Iterable", "java.util.Collection", "java.util.List",
							"java.util.Set", "java.util.Map", "java.util.Queue", "java.util.Deque",
							"java.util.Iterator", "java.util.Comparator", "java.util.Spliterator",
							"java.util.ArrayList", "java.util.LinkedList", "java.util.ArrayDeque",
							"java.util.HashMap", "java.util.LinkedHashMap", "java.util.TreeMap",
							"java.util.HashSet", "java.util.LinkedHashSet", "java.util.TreeSet",
							"java.util.Collections", "java.util.Arrays", "java.util.Objects",
							"java.util.Optional", "java.util.function", "java.util.stream");
				case TIME:
					return allowClassNames("java.time", "java.util.Date", "java.util.Calendar",
							"java.util.GregorianCalendar", "java.util.TimeZone");
				case ASYNC_INTEROP:
					return allowClassNames("java.util.concurrent.CompletionStage",
							"java.util.concurrent.CompletableFuture", "java.util.concurrent.TimeUnit");
				case FILE_IO:
					return allowClassNames("java.io", "java.nio.file", "java.nio.charset",
							"java.nio.channels");
				case NETWORK:
					return allowClassNames("java.net");
				case PROCESS:
					return allowClassNames("java.lang.Runtime", "java.lang.Process",
							"java.lang.ProcessBuilder");
				case SYSTEM:
					return allowClassNames("java.lang.System", "java.lang.Thread",
							"java.lang.ThreadGroup", "java.lang.ClassLoader");
				default:
					throw new IllegalArgumentException("Unsupported capability: " + capability);
			}
		}

		public Builder defaultClassAccess(boolean allowed) {
			this.defaultClassAccess = allowed;
			return this;
		}

		public Builder defaultMemberAccess(boolean allowed) {
			this.defaultMemberAccess = allowed;
			return this;
		}

		public ScriptAccessPolicy build() {
			List<String> allowedClasses = immutable(allowedClassPrefixes);
			List<String> deniedClasses = immutable(deniedClassPrefixes);
			List<MemberRule> allowed = immutable(allowedMembers);
			List<MemberRule> denied = immutable(deniedMembers);
			boolean classDefault = defaultClassAccess;
			boolean memberDefault = defaultMemberAccess;
			return new ScriptAccessPolicy() {
				@Override
				public boolean allowClass(String className) {
					String requiredName = Objects.requireNonNull(className, "className");
					if (matchesPrefix(deniedClasses, requiredName)) return false;
					if (matchesPrefix(allowedClasses, requiredName)) return true;
					return classDefault;
				}

				@Override
				public boolean allowMember(Class<?> owner, String memberName) {
					if (!allowClass(owner.getName())) return false;
					if (matchesHierarchy(denied, owner, memberName)) return false;
					if (matchesHierarchy(allowed, owner, memberName)) return true;
					return memberDefault;
				}
			};
		}

		private static boolean matchesPrefix(List<String> prefixes, String value) {
			for (String prefix : prefixes) if (matchesQualifiedPrefix(prefix, value)) return true;
			return false;
		}

		private static boolean matches(List<MemberRule> rules, String owner, String member) {
			for (MemberRule rule : rules) if (rule.matches(owner, member)) return true;
			return false;
		}

		private static boolean matchesQualifiedPrefix(String prefix, String value) {
			if (value.equals(prefix)) return true;
			if (prefix.endsWith(".") || prefix.endsWith("$")) {
				return value.startsWith(prefix);
			}
			return value.startsWith(prefix + ".") || value.startsWith(prefix + "$");
		}

		private static boolean matchesHierarchy(List<MemberRule> rules, Class<?> owner, String member) {
			if (owner == null) return false;
			if (matches(rules, owner.getName(), member)) return true;
			for (Class<?> interfaceType : owner.getInterfaces()) {
				if (matchesHierarchy(rules, interfaceType, member)) return true;
			}
			return matchesHierarchy(rules, owner.getSuperclass(), member);
		}

		private static String requireRule(String value, String label) {
			String rule = Objects.requireNonNull(value, label).trim();
			if (rule.isEmpty()) throw new IllegalArgumentException(label + " must not be blank");
			return rule;
		}

		private Builder allowClassNames(String... classNames) {
			for (String className : classNames) {
				allowedClassPrefixes.add(className);
			}
			return this;
		}

		private static <T> List<T> immutable(List<T> source) {
			return Collections.unmodifiableList(new ArrayList<>(source));
		}

		private static final class MemberRule {
			private final String ownerPrefix;
			private final String memberName;

			private MemberRule(String ownerPrefix, String memberName) {
				this.ownerPrefix = requireRule(ownerPrefix, "ownerPrefix");
				this.memberName = requireRule(memberName, "memberName");
			}

			private boolean matches(String owner, String member) {
				return matchesQualifiedPrefix(ownerPrefix, owner)
						&& ("*".equals(memberName) || memberName.equals(member));
			}
		}
	}
}
