package me.wattmann.rpets.data;

import lombok.NonNull;
import me.wattmann.concurrent.BukkitExecutor;
import me.wattmann.rpets.RPets;
import me.wattmann.rpets.imp.KernelReference;
import org.bukkit.ChatColor;
import org.bukkit.craftbukkit.v1_16_R2.entity.CraftEntity;
import org.bukkit.entity.Entity;
import org.bukkit.scheduler.BukkitRunnable;

import javax.print.attribute.standard.RequestingUserName;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;


public final class DataRegistry implements KernelReference {

    @NonNull
    protected final RPets kernel;
    @NonNull
    protected Set<DataProfile> cache = Collections.synchronizedSet(new HashSet<>());
    @NonNull
    private Path data_path;

    @NonNull
    protected BukkitExecutor bukkitExecutor;


    public DataRegistry(@NonNull RPets kernel) {
        this.kernel = kernel;
        this.bukkitExecutor = new BukkitExecutor(kernel);
    }

    @Override
    public void init() throws Exception {
        this.data_path = Path.of(getKernelReference().getDataFolder().toPath().toString(), "data");
        bukkitExecutor.executeTicking(this::saveCachedAsync, 0L, 20 * 10);
        kernel.getKernelFeedback().logInfo("Async save callback hooked");
    }

    @Override
    public void term() throws Exception {
        kernel.getKernelFeedback().logInfo("Saving all cached data");
        cache.iterator().forEachRemaining((entry) -> {
            try {
                kernel.getKernelFeedback().logDebug("Saving %s", entry.getUuid().toString());
                writeFile(entry);
            } catch (IOException e) {
                kernel.getKernelFeedback().logError("Failed to save data for %s", e, entry.getUuid().toString());
            }
        });
        kernel.getKernelFeedback().logInfo("Saved all cached data");
    }


    /**
     * Used to save all cached data asynchronously
     * */
    public Set<CompletableFuture<Void>> saveCachedAsync() {

        var iterator = Set.copyOf(cache).iterator();
        cache.clear();
        Set<CompletableFuture<Void>> tasks = new HashSet<>();

        while(iterator.hasNext()) {
            var data = iterator.next();
            tasks.add(saveAsync(data).whenComplete((v, throwable) -> {
                if(throwable != null)
                    kernel.getKernelFeedback().logError("Failed to save data for %s", throwable, data.getUuid().toString());
                else {
                    kernel.getKernelFeedback().logDebug("Saved data for %s", data.getUuid().toString());
                }
            }));
        }
        kernel.getKernelFeedback().logInfo("Asynchronously saved all cached data");
        return tasks;
    }

    /**
     * Asynchronously saves a {@link DataProfile} to its file
     * @param profile {@link DataProfile} to be saved
     * @return {@link CompletableFuture } of {@link Void} which completes exceptionally when writing to file was unsuccessful, NULL otherwise
     * */
    public CompletableFuture<Void> saveAsync(@NonNull DataProfile profile) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        bukkitExecutor.execute(() -> {
            try {
                future.orTimeout(1, TimeUnit.MINUTES);
                writeFile(profile);
                future.complete(null);
            } catch (IOException e) {
                future.completeExceptionally(e);
            }
        });
        return future;
    }

    public void save(@NonNull DataProfile profile) {
        try {
            writeFile(profile);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * Asynchronously fetches a {@link DataProfile} using a specified identifier
     *
     * @param uuid {@link UUID} identifier of the data file
     * @param create in case a file is not found, should this return a new entry?
     * @return {@link CompletableFuture } of {@link DataProfile}, when successful, null if an exception was thrown,
     * or null if not found and the boolean create has been set to false
     */
    public CompletableFuture<DataProfile> fetch(UUID uuid, boolean create) {
        return CompletableFuture.supplyAsync(() -> {
            return cache.stream().filter((entry) -> {
                return entry.getUuid().equals(uuid);
            }).findFirst().orElseGet(() -> {
                DataProfile profile = null;
                try {
                    profile = readFile(uuid);
                    System.out.println("reading profile");
                } catch (IOException e) {
                    if (create)
                        profile = DataProfile.builder().uuid(uuid).data(new DataProfile.PetData()).build();
                    System.out.println("created new profile");
                }
                if (profile != null)
                    cache.add(profile);
                System.out.println("returned profile");
                return profile;
            });
        }, bukkitExecutor).orTimeout(1, TimeUnit.MINUTES);
    }

    private @NonNull DataProfile readFile(@NonNull UUID uuid) throws IOException {
        try (InputStream in = new FileInputStream(data_path.resolve(uuid.toString() + ".bin").toFile())) {
            System.out.println("reading file");
            DataProfile.DataProfileBuilder builder = DataProfile.builder();

            StringBuffer key_buffer = new StringBuffer();
            ByteBuffer val_buffer = ByteBuffer.allocate(Integer.BYTES);
            Map<String, Integer> data = new HashMap<>();

            boolean key = true;
            int readen;

            while((readen = in.read()) != -0x1)
            {
                if (key) {
                    if (readen == 0x0)
                        key = false;
                    else key_buffer.append((char) readen);
                } else {
                    val_buffer.put((byte) readen);
                    if(!val_buffer.hasRemaining()) {
                        //END OF RECORD
                        key = true;
                        data.put(key_buffer.toString(), val_buffer.getInt(0));
                        System.out.println("read " + key_buffer.toString() + " : " + val_buffer.getInt(0));
                        key_buffer.setLength(0);
                        val_buffer.clear();
                    }
                }
            }
            return builder.data(new DataProfile.PetData(data)).uuid(uuid).build();
        }

    }

    private void writeFile(@NonNull DataProfile profile) throws IOException {
        try (OutputStream out = new FileOutputStream(data_path.resolve(profile.getUuid() + ".bin").toFile())) {
            ByteBuffer buffer = ByteBuffer.allocate(Integer.BYTES);
            for (Map.Entry<String, Integer> datum : profile.getData()) {
                out.write(datum.getKey().getBytes());
                out.write(0x0);
                out.write(buffer.putInt(0, datum.getValue()).array());
                out.flush();
            }
        }
    }

    @Override
    public @NonNull RPets getKernelReference() {
        return kernel;
    }
}