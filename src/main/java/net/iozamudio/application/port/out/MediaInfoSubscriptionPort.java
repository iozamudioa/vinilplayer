package net.iozamudio.application.port.out;

import net.iozamudio.model.MediaInfo;

import java.util.function.Consumer;

public interface MediaInfoSubscriptionPort {
    void subscribe(Consumer<MediaInfo> onMediaUpdate) throws Exception;

    void unsubscribe();
}