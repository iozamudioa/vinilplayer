package net.iozamudio.application.port.out;

import net.iozamudio.model.MediaInfo;

public interface MediaInfoProviderPort {
    MediaInfo getCurrent() throws Exception;
}