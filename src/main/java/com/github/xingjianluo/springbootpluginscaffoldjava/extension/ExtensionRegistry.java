package com.github.xingjianluo.springbootpluginscaffoldjava.extension;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 已启用扩展的运行时注册中心。持有当前生效的不可变快照，
 * 支持原子刷新：刷新期间查询不会观察到半加载或新旧混用状态，
 * 相同配置重复刷新幂等，切换失败整体回退（旧快照保持不变）。
 *
 * <p>并发语义：
 * <ul>
 *   <li>读操作（{@link #current()}/{@link #listExtensions()}/{@link #find(String)}）完全无锁，
 *       只读 {@code volatile} 快照引用，任何时刻看到的都是一个完整的旧快照或完整的新快照；</li>
 *   <li>写操作（{@link #refresh}）串行化：加载在锁外完成（不触碰当前状态），
 *       成功后才在锁内原子替换引用，失败直接丢弃临时快照，旧状态原封不动。</li>
 * </ul>
 */
public class ExtensionRegistry {

    private static final Logger log = LoggerFactory.getLogger(ExtensionRegistry.class);

    /** 尚未初始化时使用的空快照 */
    private static final ExtensionSnapshot EMPTY = new ExtensionSnapshot(List.of(), "");

    private final ExtensionLoader loader;

    private volatile ExtensionSnapshot snapshot = EMPTY;

    /** 仅用于保证刷新串行化；读路径不获取该锁 */
    private final ReentrantLock refreshLock = new ReentrantLock();

    public ExtensionRegistry(ExtensionLoader loader) {
        this.loader = loader;
    }

    /** 当前生效快照（永不为 null；初始化前为空集合快照）。 */
    public ExtensionSnapshot current() {
        return snapshot;
    }

    /** 按加载顺序返回已启用扩展的不可变列表。 */
    public List<LoadedExtension> listExtensions() {
        return snapshot.extensions();
    }

    /** 按名称查询已启用扩展。 */
    public Optional<LoadedExtension> find(String name) {
        ExtensionSnapshot current = snapshot;
        return current.extensions().stream()
                .filter(e -> e.name().equals(name))
                .findFirst();
    }

    /**
     * 原子地刷新已启用集合。
     * <ol>
     *   <li>先用加载器在临时结果上完成全部校验与实例化，当前对外状态完全不受影响；</li>
     *   <li>加载失败时异常原样抛出，当前快照保持不变，实现整体回退；</li>
     *   <li>加载成功后比较配置签名，签名相同则直接返回 {@code changed=false}（幂等，不替换引用）；</li>
     *   <li>签名不同才在锁内以双检方式原子替换快照引用。</li>
     * </ol>
     *
     * @return 刷新结果（是否实际发生切换）
     * @throws ExtensionLoadingException 新配置无法通过校验时抛出，旧状态不受影响
     */
    public RefreshResult refresh(List<ExtensionDeclaration> declarations) {
        log.info("收到扩展刷新请求，声明数量={}", declarations == null ? 0 : declarations.size());
        // 加载在“影子状态”上完成，失败不会触碰当前快照
        ExtensionSnapshot candidate = loader.load(declarations);

        ExtensionSnapshot current = snapshot;
        if (candidate.signature().equals(current.signature())
                && current != EMPTY) {
            log.info("配置签名与当前快照一致，刷新为幂等空操作，签名={}", candidate.signature());
            return new RefreshResult(false, current);
        }
        refreshLock.lock();
        try {
            // 双检：可能在加载期间已被其它线程用相同配置刷新过
            current = snapshot;
            if (candidate.signature().equals(current.signature()) && current != EMPTY) {
                log.info("锁内双检发现配置已生效，跳过重复切换，签名={}", candidate.signature());
                return new RefreshResult(false, current);
            }
            this.snapshot = candidate;
            log.info("扩展快照已原子切换，新签名={}，启用数量={}", candidate.signature(),
                    candidate.extensions().size());
            return new RefreshResult(true, candidate);
        } finally {
            refreshLock.unlock();
        }
    }

    /** 刷新结果。 */
    public record RefreshResult(boolean changed, ExtensionSnapshot snapshot) {
    }
}
