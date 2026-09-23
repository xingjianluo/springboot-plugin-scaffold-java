package com.github.xingjianluo.springbootpluginscaffoldjava.extension;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 注册中心默认实现：线程安全，登记结果按类名稳定排序，保证可复现。
 */
public class DefaultExtensionRegistry implements ExtensionRegistry {

    private final CopyOnWriteArrayList<Class<? extends Extension>> types = new CopyOnWriteArrayList<>();

    @Override
    public void register(Class<? extends Extension> type) {
        if (type == null) {
            throw new IllegalArgumentException("extension type must not be null");
        }
        boolean exists = types.stream().anyMatch(t -> t.getName().equals(type.getName()));
        if (!exists) {
            types.add(type);
        }
    }

    @Override
    public Optional<Class<? extends Extension>> findByClassName(String className) {
        return types.stream()
                .filter(t -> t.getName().equals(className))
                .findFirst();
    }

    @Override
    public List<Class<? extends Extension>> all() {
        return types.stream()
                .sorted(Comparator.comparing(Class::getName))
                .toList();
    }
}
