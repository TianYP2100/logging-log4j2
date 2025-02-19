/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache license, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the license for the specific language governing permissions and
 * limitations under the license.
 */

package org.apache.logging.log4j.plugins.di;

import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.plugins.FactoryType;
import org.apache.logging.log4j.plugins.Inject;
import org.apache.logging.log4j.plugins.Named;
import org.apache.logging.log4j.plugins.Node;
import org.apache.logging.log4j.plugins.QualifierType;
import org.apache.logging.log4j.plugins.ScopeType;
import org.apache.logging.log4j.plugins.Singleton;
import org.apache.logging.log4j.plugins.convert.TypeConverter;
import org.apache.logging.log4j.plugins.name.AnnotatedElementAliasesProvider;
import org.apache.logging.log4j.plugins.name.AnnotatedElementNameProvider;
import org.apache.logging.log4j.plugins.util.AnnotationUtil;
import org.apache.logging.log4j.plugins.util.PluginManager;
import org.apache.logging.log4j.plugins.util.PluginType;
import org.apache.logging.log4j.plugins.util.TypeUtil;
import org.apache.logging.log4j.plugins.validation.Constraint;
import org.apache.logging.log4j.plugins.validation.ConstraintValidationException;
import org.apache.logging.log4j.plugins.validation.ConstraintValidator;
import org.apache.logging.log4j.plugins.visit.NodeVisitor;
import org.apache.logging.log4j.status.StatusLogger;
import org.apache.logging.log4j.util.EnglishEnums;
import org.apache.logging.log4j.util.LazyValue;
import org.apache.logging.log4j.util.ServiceRegistry;
import org.apache.logging.log4j.util.StringBuilders;

import java.lang.annotation.Annotation;
import java.lang.reflect.AccessibleObject;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.UnknownFormatConversionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

class DefaultInjector implements Injector {
    private static final Logger LOGGER = StatusLogger.getLogger();

    private final BindingMap bindingMap;
    private final Map<Class<? extends Annotation>, Scope> scopes = new ConcurrentHashMap<>();
    private final Map<Type, TypeConverter<?>> typeConverters = new ConcurrentHashMap<>();
    private ReflectionAccessor accessor = object -> object.setAccessible(true);

    DefaultInjector() {
        bindingMap = new BindingMap();
        bindingMap.put(Key.forClass(Injector.class), () -> this);
        scopes.put(Singleton.class, new SingletonScope());
    }

    DefaultInjector(final DefaultInjector original) {
        bindingMap = new BindingMap(original.bindingMap);
        scopes.putAll(original.scopes);
        typeConverters.putAll(original.typeConverters);
        accessor = original.accessor;
    }

    @Override
    public void init() {
        final List<InjectorCallback> callbacks = ServiceRegistry.getInstance()
                .getServices(InjectorCallback.class, layer -> ServiceLoader.load(layer, InjectorCallback.class), null);
        callbacks.sort(InjectorCallback.COMPARATOR);
        for (final InjectorCallback callback : callbacks) {
            try {
                callback.configure(this);
            } catch (final Exception e) {
                LOGGER.error("Unable to configure injection callback {}: {}", callback, e.getMessage(), e);
            }
        }
    }

    @Override
    public Injector copy() {
        return new DefaultInjector(this);
    }

    @Override
    public <T> Supplier<T> getFactory(final Key<T> key) {
        return getFactory(key, Set.of(), null, Set.of());
    }

    @Override
    public TypeConverter<?> getTypeConverter(final Type type) {
        if (typeConverters.isEmpty()) {
            synchronized (typeConverters) {
                if (typeConverters.isEmpty()) {
                    LOGGER.trace("Initializing type converters");
                    initializeTypeConverters();
                }
            }
        }
        final TypeConverter<?> primary = typeConverters.get(type);
        // cached type converters
        if (primary != null) {
            return primary;
        }
        // dynamic enum support
        if (type instanceof Class<?>) {
            final Class<?> clazz = (Class<?>) type;
            if (clazz.isEnum()) {
                return registerTypeConverter(type, s -> EnglishEnums.valueOf(clazz.asSubclass(Enum.class), s));
            }
        }
        // look for compatible converters
        for (final Map.Entry<Type, TypeConverter<?>> entry : typeConverters.entrySet()) {
            final Type key = entry.getKey();
            if (TypeUtil.isAssignable(type, key)) {
                LOGGER.debug("Found compatible TypeConverter<{}> for type [{}].", key, type);
                final TypeConverter<?> value = entry.getValue();
                return registerTypeConverter(type, value);
            }
        }
        throw new UnknownFormatConversionException(type.toString());
    }

    @Override
    public void injectMembers(final Object instance) {
        injectMembers(Key.forClass(instance.getClass()), null, instance, Set.of(), null);
    }

    @Override
    public <T> T configure(final Node node) {
        final PluginType<?> type = node.getType();
        if (type != null && type.isDeferChildren()) {
            inject(node);
        } else {
            node.getChildren().forEach(this::configure);
            if (type == null) {
                if (node.getParent() == null) {
                    LOGGER.error("Unable to locate plugin for node {}", node.getName());
                }
            } else {
                inject(node);
            }
        }
        verifyAttributesConsumed(node);
        verifyChildrenConsumed(node);
        return node.getObject();
    }

    @Override
    public void registerScope(final Class<? extends Annotation> scopeType, final Scope scope) {
        scopes.put(scopeType, scope);
    }

    @Override
    public Scope getScope(final Class<? extends Annotation> scopeType) {
        return scopes.get(scopeType);
    }

    @Override
    public void registerBundle(final Object bundle) {
        if (bundle instanceof Class<?>) {
            registerModuleInstance(getInstance((Class<?>) bundle));
        } else {
            registerModuleInstance(bundle);
        }
    }

    @Override
    public <T> Injector registerBinding(final Key<T> key, final Supplier<? extends T> factory) {
        bindingMap.put(key, factory::get);
        return this;
    }

    @Override
    public <T> Injector registerBindingIfAbsent(final Key<T> key, final Supplier<? extends T> factory) {
        bindingMap.bindIfAbsent(key, factory::get);
        return this;
    }

    @Override
    public void removeBinding(final Key<?> key) {
        bindingMap.remove(key);
    }

    @Override
    public void setReflectionAccessor(final ReflectionAccessor accessor) {
        this.accessor = accessor;
    }

    private <M extends AccessibleObject & Member> void makeAccessible(final M member, final Object instance) {
        final boolean isStatic = Modifier.isStatic(member.getModifiers());
        if (!member.canAccess(isStatic ? null : instance)) {
            accessor.makeAccessible(member);
        }
    }

    private <T> Supplier<T> getFactory(
            final InjectionPoint<T> point, final Node node, final Set<Key<?>> chain, final StringBuilder debugLog) {
        final AnnotatedElement element = point.getElement();
        final Key<? extends NodeVisitor> visitorKey = NodeVisitor.keyFor(element);
        final NodeVisitor visitor = visitorKey != null ? getInstance(visitorKey) : null;
        if (visitor != null) {
            if (element instanceof Field) {
                return () -> TypeUtil.cast(visitor.visitField((Field) element, node, debugLog));
            } else {
                return () -> TypeUtil.cast(visitor.visitParameter((Parameter) element, node, debugLog));
            }
        }
        final Key<T> key = point.getKey();
        final Collection<String> aliases = point.getAliases();
        final Key<T> suppliedType = key.getSuppliedType();
        return suppliedType != null ? getFactory(suppliedType, aliases, node, Set.of()) : getFactory(key, aliases, node, chain);
    }

    private <T> Supplier<T> getFactory(
            final Key<T> key, final Collection<String> aliases, final Node node, final Set<Key<?>> chain) {
        final Binding<T> existing = bindingMap.get(key, aliases);
        if (existing != null) {
            return existing.getSupplier();
        }
        final Class<T> rawType = key.getRawType();
        final Scope scope = getScopeForType(rawType);
        if (rawType == PluginManager.class && key.getQualifierType() == Named.class) {
            final Supplier<T> factory = () -> {
                final var manager = new PluginManager(key.getName());
                final Binding<List<String>> pluginPackagesBinding = bindingMap.get(Keys.PLUGIN_PACKAGES_KEY, List.of());
                manager.collectPlugins(pluginPackagesBinding != null ? pluginPackagesBinding.getSupplier().get() : List.of());
                return TypeUtil.cast(manager);
            };
            bindingMap.put(key, scope.get(key, factory));
            return bindingMap.get(key, aliases).getSupplier();
        }
        final Supplier<T> instanceSupplier = () -> {
            final StringBuilder debugLog = new StringBuilder();
            final T instance = TypeUtil.cast(getInjectableInstance(key, node, chain, debugLog));
            injectMembers(key, node, instance, chain, debugLog);
            return instance;
        };
        return bindingMap.bindIfAbsent(key, scope.get(key, instanceSupplier));
    }

    private Object getInjectableInstance(
            final Key<?> key, final Node node, final Set<Key<?>> chain, final StringBuilder debugLog) {
        final Class<?> rawType = key.getRawType();
        validate(rawType, key.getName(), rawType);
        final Constructor<?> constructor = getInjectableConstructor(key, chain);
        makeAccessible(constructor, null);
        final List<InjectionPoint<?>> points = InjectionPoint.fromExecutable(constructor);
        final var args = getArguments(key, node, points, chain, debugLog);
        return newInstance(constructor, args);
    }

    private void validate(final AnnotatedElement element, final String name, final Object value) {
        int errors = 0;
        for (final Annotation annotation : element.getAnnotations()) {
            final Class<? extends Annotation> annotationType = annotation.annotationType();
            final Constraint constraint = annotationType.getAnnotation(Constraint.class);
            if (constraint != null && isCompatibleValidator(constraint, annotationType)) {
                final ConstraintValidator<? extends Annotation> validator = getInstance(constraint.value());
                initializeConstraintValidator(validator, annotation);
                if (!validator.isValid(name, value)) {
                    errors++;
                }
            }
        }
        if (errors > 0) {
            throw new ConstraintValidationException(element, name, value);
        }
    }

    private void initializeTypeConverters() {
        final PluginManager manager = getInstance(TypeConverter.PLUGIN_MANAGER_KEY);
        for (final PluginType<?> knownType : manager.getPlugins().values()) {
            final Class<?> pluginClass = knownType.getPluginClass();
            final Type type = getTypeConverterSupportedType(pluginClass);
            final TypeConverter<?> converter = getInstance(pluginClass.asSubclass(TypeConverter.class));
            registerTypeConverter(type, converter);
        }
        registerTypeConverter(Boolean.class, Boolean::valueOf);
        registerTypeAlias(Boolean.class, Boolean.TYPE);
        registerTypeConverter(Byte.class, Byte::valueOf);
        registerTypeAlias(Byte.class, Byte.TYPE);
        registerTypeConverter(Character.class, s -> {
            if (s.length() != 1) {
                throw new IllegalArgumentException("Character string must be of length 1: " + s);
            }
            return s.toCharArray()[0];
        });
        registerTypeAlias(Character.class, Character.TYPE);
        registerTypeConverter(Double.class, Double::valueOf);
        registerTypeAlias(Double.class, Double.TYPE);
        registerTypeConverter(Float.class, Float::valueOf);
        registerTypeAlias(Float.class, Float.TYPE);
        registerTypeConverter(Integer.class, Integer::valueOf);
        registerTypeAlias(Integer.class, Integer.TYPE);
        registerTypeConverter(Long.class, Long::valueOf);
        registerTypeAlias(Long.class, Long.TYPE);
        registerTypeConverter(Short.class, Short::valueOf);
        registerTypeAlias(Short.class, Short.TYPE);
        registerTypeConverter(String.class, s -> s);
    }

    private TypeConverter<?> registerTypeConverter(final Type type, final TypeConverter<?> converter) {
        final TypeConverter<?> conflictingConverter = typeConverters.get(type);
        if (conflictingConverter != null) {
            final boolean overridable;
            if (converter instanceof Comparable) {
                @SuppressWarnings("unchecked")
                final Comparable<TypeConverter<?>> comparableConverter =
                        (Comparable<TypeConverter<?>>) converter;
                overridable = comparableConverter.compareTo(conflictingConverter) < 0;
            } else if (conflictingConverter instanceof Comparable) {
                @SuppressWarnings("unchecked")
                final Comparable<TypeConverter<?>> comparableConflictingConverter =
                        (Comparable<TypeConverter<?>>) conflictingConverter;
                overridable = comparableConflictingConverter.compareTo(converter) > 0;
            } else {
                overridable = false;
            }
            if (overridable) {
                LOGGER.debug(
                        "Replacing TypeConverter [{}] for type [{}] with [{}] after comparison.",
                        conflictingConverter, type, converter);
                typeConverters.put(type, converter);
                return converter;
            } else {
                LOGGER.warn(
                        "Ignoring TypeConverter [{}] for type [{}] that conflicts with [{}], since they are not comparable.",
                        converter, type, conflictingConverter);
                return conflictingConverter;
            }
        } else {
            typeConverters.put(type, converter);
            return converter;
        }
    }

    private void registerTypeAlias(final Type knownType, final Type aliasType) {
        final TypeConverter<?> converter = typeConverters.get(knownType);
        if (converter != null) {
            typeConverters.put(aliasType, converter);
        } else {
            LOGGER.error("Cannot locate type converter for {}", knownType);
        }
    }

    private void injectMembers(
            final Key<?> key, final Node node, final Object instance, final Set<Key<?>> chain, final StringBuilder debugLog) {
        injectFields(key.getRawType(), node, instance, debugLog);
        injectMethods(key, node, instance, chain, debugLog);
    }

    private void injectFields(final Class<?> rawType, final Node node, final Object instance, final StringBuilder debugLog) {
        for (Class<?> clazz = rawType; clazz != Object.class; clazz = clazz.getSuperclass()) {
            for (final Field field : clazz.getDeclaredFields()) {
                if (isInjectable(field)) {
                    injectField(field, node, instance, debugLog);
                }
            }
        }
    }

    private <T> void injectField(final Field field, final Node node, final Object instance, final StringBuilder debugLog) {
        makeAccessible(field, instance);
        final InjectionPoint<T> point = InjectionPoint.forField(field);
        final Supplier<T> factory = getFactory(point, node, Set.of(), debugLog);
        final Key<T> key = point.getKey();
        final Object value = key.getRawType() == Supplier.class ? factory : factory.get();
        if (value != null) {
            setField(field, instance, value);
        }
        if (AnnotationUtil.isMetaAnnotationPresent(field, Constraint.class)) {
            final Object fieldValue = getField(field, instance);
            validate(field, key.getName(), fieldValue);
        }
    }

    private void injectMethods(
            final Key<?> key, final Node node, final Object instance, final Set<Key<?>> chain, final StringBuilder debugLog) {
        final Class<?> rawType = key.getRawType();
        final List<Method> injectMethodsWithNoArgs = new ArrayList<>();
        for (Class<?> clazz = rawType; clazz != Object.class; clazz = clazz.getSuperclass()) {
            for (final Method method : clazz.getDeclaredMethods()) {
                if (isInjectable(method)) {
                    makeAccessible(method, instance);
                    if (method.getParameterCount() == 0) {
                        injectMethodsWithNoArgs.add(method);
                    } else {
                        final List<InjectionPoint<?>> injectionPoints = InjectionPoint.fromExecutable(method);
                        final var args = getArguments(key, node, injectionPoints, chain, debugLog);
                        invokeMethod(method, instance, args);
                    }
                }
            }
        }
        injectMethodsWithNoArgs.forEach(method -> invokeMethod(method, instance));
    }

    private void inject(final Node node) {
        final PluginType<?> type = node.getType();
        final Class<?> pluginClass = type.getPluginClass();
        final List<Node> children = node.getChildren();
        // support for plugin classes that implement Map; unused in Log4j, but possibly used by custom plugins
        if (Map.class.isAssignableFrom(pluginClass)) {
            final Map<String, Object> map = new LinkedHashMap<>(children.size());
            children.forEach(child -> map.put(child.getName(), child.getObject()));
            node.setObject(map);
            return;
        }
        // support for plugin classes that implement Collection; unused in Log4j, but possibly used by custom plugins
        if (Collection.class.isAssignableFrom(pluginClass)) {
            final List<Object> list = new ArrayList<>(children.size());
            children.forEach(child -> list.add(child.getObject()));
            node.setObject(list);
            return;
        }
        try {
            validate(pluginClass, type.getElementName(), pluginClass);
            final StringBuilder debugLog = new StringBuilder();
            final Object instance = getInjectablePluginInstance(node, debugLog);
            if (instance instanceof Supplier<?>) {
                // configure plugin builder class and obtain plugin from that
                injectMembers(Key.forClass(instance.getClass()), node, instance, Set.of(), debugLog);
                node.setObject(((Supplier<?>) instance).get());
            } else {
                // usually created via static plugin factory method, but otherwise assume this is the final plugin instance
                node.setObject(instance);
            }
            LOGGER.debug("Configured plugin element {}[{}]", node.getName(), debugLog);
        } catch (final Throwable e) {
            LOGGER.error("Could not configure plugin element {}: {}", node.getName(), e.toString(), e);
        }
    }

    private Object getInjectablePluginInstance(final Node node, final StringBuilder debugLog) {
        final PluginType<?> type = node.getType();
        final Class<?> rawType = type.getPluginClass();
        final Key<?> key = Key.forClass(rawType);
        final Executable factory = Stream.of(rawType.getDeclaredMethods())
                .filter(method -> Modifier.isStatic(method.getModifiers()) &&
                        AnnotationUtil.isMetaAnnotationPresent(method, FactoryType.class))
                .min(Comparator.comparingInt(Method::getParameterCount).thenComparing(Method::getReturnType, (c1, c2) -> {
                    if (c1.equals(c2)) {
                        return 0;
                    } else if (Supplier.class.isAssignableFrom(c1)) {
                        return -1;
                    } else if (Supplier.class.isAssignableFrom(c2)) {
                        return 1;
                    } else {
                        return c1.getName().compareTo(c2.getName());
                    }
                }))
                .map(Executable.class::cast)
                .orElseGet(() -> getInjectableConstructor(key, Set.of()));
        final List<InjectionPoint<?>> points = InjectionPoint.fromExecutable(factory);
        if (!factory.canAccess(null)) {
            accessor.makeAccessible(factory);
        }
        final var args = getArguments(key, node, points, Set.of(), debugLog);
        if (factory instanceof Method) {
            return invokeMethod((Method) factory, null, args);
        } else {
            return newInstance((Constructor<?>) factory, args);
        }
    }

    private void registerModuleInstance(final Object module) {
        final Class<?> moduleClass = module.getClass();
        final List<Method> providerMethods = new ArrayList<>();
        Stream.<Class<?>>iterate(moduleClass, c -> c != Object.class, Class::getSuperclass)
                .flatMap(c -> Stream.of(c.getDeclaredMethods()))
                .filter(method -> AnnotationUtil.isMetaAnnotationPresent(method, FactoryType.class))
                .forEachOrdered(method -> {
                    if (method.getDeclaringClass().equals(moduleClass) || providerMethods.stream().noneMatch(m ->
                            m.getName().equals(method.getName()) &&
                                    Arrays.equals(m.getParameterTypes(), method.getParameterTypes()))) {
                        providerMethods.add(method);
                        createMethodBindings(module, method).forEach(binding -> {
                            final var key = binding.getKey();
                            if (!bindingMap.putIfAbsent(key, binding.getSupplier())) {
                                throw new InjectException(String.format(
                                        "Duplicate @Factory method (%s: %s) found for %s", moduleClass, method, key));
                            }
                        });
                    }
                });
    }

    private <T> List<Binding<T>> createMethodBindings(final Object instance, final Method method) {
        makeAccessible(method, instance);
        final Key<T> primaryKey = Key.forMethod(method);
        final List<InjectionPoint<?>> points = InjectionPoint.fromExecutable(method);
        final var argumentFactories = getArgumentFactories(primaryKey, null, points, Set.of(primaryKey), null);
        final Supplier<T> unscoped = () -> {
            final var args = argumentFactories.entrySet()
                    .stream()
                    .map(e -> {
                        final Parameter parameter = e.getKey();
                        final String name = AnnotatedElementNameProvider.getName(parameter);
                        final Object value = e.getValue().get();
                        validate(parameter, name, value);
                        return value;
                    })
                    .toArray();
            return rethrow(() -> TypeUtil.cast(invokeMethod(method, instance, args)));
        };
        final Supplier<T> factory = getScopeForMethod(method).get(primaryKey, unscoped);
        final Collection<String> aliases = AnnotatedElementAliasesProvider.getAliases(method);
        final List<Binding<T>> bindings = new ArrayList<>(1 + aliases.size());
        bindings.add(Binding.bind(primaryKey, factory));
        for (final String alias : aliases) {
            bindings.add(Binding.bind(primaryKey.withName(alias), factory));
        }
        return bindings;
    }

    private Object[] getArguments(
            final Key<?> key, final Node node, final List<InjectionPoint<?>> points, final Set<Key<?>> chain,
            final StringBuilder debugLog) {
        return getArgumentFactories(key, node, points, chain, debugLog)
                .entrySet()
                .stream()
                .map(e -> {
                    final Parameter parameter = e.getKey();
                    final String name = AnnotatedElementNameProvider.getName(parameter);
                    final Object value = e.getValue().get();
                    validate(parameter, name, value);
                    return value;
                })
                .toArray();
    }

    private Map<Parameter, Supplier<?>> getArgumentFactories(
            final Key<?> key, final Node node, final List<InjectionPoint<?>> points, final Set<Key<?>> chain,
            final StringBuilder debugLog) {
        final Map<Parameter, Supplier<?>> argFactories = new LinkedHashMap<>();
        for (final InjectionPoint<?> point : points) {
            final Key<?> parameterKey = point.getKey();
            final Parameter parameter = (Parameter) point.getElement();
            if (parameterKey.getRawType().equals(Supplier.class)) {
                argFactories.put(parameter, () -> getFactory(point, node, chain, debugLog));
            } else {
                final var newChain = chain(chain, key);
                if (newChain.contains(parameterKey)) {
                    final StringBuilder sb = new StringBuilder("Circular dependency encountered: ");
                    for (final Key<?> chainKey : newChain) {
                        sb.append(chainKey).append(" -> ");
                    }
                    sb.append(parameterKey);
                    throw new InjectException(sb.toString());
                }
                argFactories.put(parameter, () -> getFactory(point, node, newChain, debugLog).get());
            }
        }
        return argFactories;
    }

    private Scope getScopeForMethod(final Method method) {
        final Annotation methodScope = AnnotationUtil.getMetaAnnotation(method, ScopeType.class);
        return methodScope != null ? scopes.get(methodScope.annotationType()) : getScopeForType(method.getReturnType());
    }

    private Scope getScopeForType(final Class<?> type) {
        final Annotation scope = AnnotationUtil.getMetaAnnotation(type, ScopeType.class);
        return scope != null ? scopes.get(scope.annotationType()) : DefaultScope.INSTANCE;
    }

    private static boolean isCompatibleValidator(
            final Constraint constraint, final Class<? extends Annotation> annotationType) {
        for (final Type type : constraint.value().getGenericInterfaces()) {
            if (type instanceof ParameterizedType) {
                ParameterizedType parameterizedType = (ParameterizedType) type;
                if (parameterizedType.getRawType() == ConstraintValidator.class &&
                        parameterizedType.getActualTypeArguments()[0] == annotationType) {
                    return true;
                }
            }
        }
        return false;
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static void initializeConstraintValidator(
            final ConstraintValidator<? extends Annotation> validator, final Annotation annotation) {
        // runtime type checking ensures this raw type usage is correct
        ((ConstraintValidator) validator).initialize(annotation);
    }

    private static Type getTypeConverterSupportedType(final Class<?> clazz) {
        for (final Type type : clazz.getGenericInterfaces()) {
            if (type instanceof ParameterizedType) {
                final ParameterizedType parameterizedType = (ParameterizedType) type;
                if (parameterizedType.getRawType() == TypeConverter.class) {
                    return parameterizedType.getActualTypeArguments()[0];
                }
            }
        }
        return Void.TYPE;
    }

    private static void verifyAttributesConsumed(final Node node) {
        final Map<String, String> attrs = node.getAttributes();
        if (!attrs.isEmpty()) {
            final StringBuilder sb = new StringBuilder();
            for (final String key : attrs.keySet()) {
                if (sb.length() == 0) {
                    sb.append(node.getName());
                    sb.append(" contains ");
                    if (attrs.size() == 1) {
                        sb.append("an invalid element or attribute ");
                    } else {
                        sb.append("invalid attributes ");
                    }
                } else {
                    sb.append(", ");
                }
                StringBuilders.appendDqValue(sb, key);
            }
            LOGGER.error(sb.toString());
        }
    }

    private static void verifyChildrenConsumed(final Node node) {
        final PluginType<?> type = node.getType();
        if (type != null && !type.isDeferChildren() && node.hasChildren()) {
            for (final Node child : node.getChildren()) {
                final String nodeType = node.getType().getElementName();
                final String start = nodeType.equals(node.getName()) ? node.getName() : nodeType + ' ' + node.getName();
                LOGGER.error("{} has no field or parameter that matches element {}", start, child.getName());
            }
        }
    }

    private static Set<Key<?>> chain(final Set<Key<?>> chain, final Key<?> newKey) {
        if (chain == null || chain.isEmpty()) {
            return Set.of(newKey);
        }
        final var newChain = new LinkedHashSet<>(chain);
        newChain.add(newKey);
        return newChain;
    }

    private static <T> Constructor<T> getInjectableConstructor(final Key<T> key, final Set<Key<?>> chain) {
        final Class<T> rawType = key.getRawType();
        final List<Constructor<?>> injectConstructors = Stream.of(rawType.getDeclaredConstructors())
                .filter(constructor -> constructor.isAnnotationPresent(Inject.class))
                .collect(Collectors.toList());
        if (injectConstructors.size() > 1) {
            throw new InjectException("Multiple @Inject constructors found in " + rawType);
        }
        if (injectConstructors.size() == 1) {
            return TypeUtil.cast(injectConstructors.get(0));
        }
        try {
            return rawType.getDeclaredConstructor();
        } catch (final NoSuchMethodException ignored) {
        }
        try {
            return rawType.getConstructor();
        } catch (final NoSuchMethodException ignored) {
        }
        final List<Key<?>> keys = new ArrayList<>(chain);
        keys.add(0, key);
        final String prefix = chain.isEmpty() ? "" : "chain ";
        final String keysToString =
                prefix + keys.stream().map(Key::toString).collect(Collectors.joining(" -> "));
        throw new InjectException(
                "No @Inject constructors or no-arg constructor found for " + keysToString);
    }

    private static boolean isInjectable(final Field field) {
        return field.isAnnotationPresent(Inject.class) || AnnotationUtil.isMetaAnnotationPresent(field, QualifierType.class);
    }

    private static boolean isInjectable(final Method method) {
        return method.isAnnotationPresent(Inject.class) ||
                !AnnotationUtil.isMetaAnnotationPresent(method, FactoryType.class) &&
                        Stream.of(method.getParameters()).anyMatch(
                                parameter -> AnnotationUtil.isMetaAnnotationPresent(parameter, QualifierType.class));
    }

    private static Object getField(final Field field, final Object instance) {
        try {
            return field.get(instance);
        } catch (final IllegalAccessException e) {
            throw errorFrom(e);
        }
    }

    private static void setField(final Field field, final Object instance, final Object value) {
        try {
            field.set(instance, value);
        } catch (final IllegalAccessException e) {
            throw errorFrom(e);
        }
    }

    private static Object invokeMethod(final Method method, final Object instance, final Object... args) {
        try {
            return method.invoke(instance, args);
        } catch (final InvocationTargetException e) {
            throw new InjectException(e.getMessage(), e.getCause());
        } catch (final IllegalAccessException e) {
            throw errorFrom(e);
        }
    }

    private static <T> T newInstance(final Constructor<T> constructor, final Object[] args) {
        try {
            return constructor.newInstance(args);
        } catch (final InvocationTargetException e) {
            throw new InjectException(e.getMessage(), e.getCause());
        } catch (final IllegalAccessException e) {
            throw errorFrom(e);
        } catch (final InstantiationException e) {
            throw new InjectException(e.getMessage(), e);
        }
    }

    private static IllegalAccessError errorFrom(final IllegalAccessException e) {
        final IllegalAccessError error = new IllegalAccessError(e.getMessage());
        error.initCause(e);
        return error;
    }

    private static <T> T rethrow(final CheckedSupplier<T> supplier) {
        try {
            return supplier.get();
        } catch (final Throwable e) {
            rethrow(e);
            throw new IllegalStateException("unreachable", e);
        }
    }

    // type inference and erasure ensures that checked exceptions can be thrown here without being checked anymore
    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void rethrow(final Throwable t) throws T {
        throw (T) t;
    }

    @FunctionalInterface
    private interface CheckedSupplier<T> {
        T get() throws Throwable;
    }

    private static class SingletonScope implements Scope {
        private final Map<Key<?>, Supplier<?>> singletonProviders = new ConcurrentHashMap<>();

        @Override
        public <T> Supplier<T> get(final Key<T> key, final Supplier<T> unscoped) {
            return TypeUtil.cast(singletonProviders.computeIfAbsent(key, ignored -> new LazyValue<>(unscoped)));
        }

        @Override
        public String toString() {
            return "[SingletonScope; size=" + singletonProviders.size() + "]";
        }
    }

    private enum DefaultScope implements Scope {
        INSTANCE;

        @Override
        public <T> Supplier<T> get(final Key<T> key, final Supplier<T> unscoped) {
            return unscoped;
        }

        @Override
        public String toString() {
            return "[Unscoped]";
        }
    }
}
