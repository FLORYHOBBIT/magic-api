package org.ssssssss.magicapi.upload.resource;

import org.springframework.context.event.EventListener;
import org.ssssssss.magicapi.core.event.FileEvent;
import org.ssssssss.magicapi.core.event.GroupEvent;
import org.ssssssss.magicapi.core.service.AbstractMagicDynamicRegistry;

/**
 * 上传通道注册表，按通道名称查找
 */
public class UploadChannelMagicDynamicRegistry extends AbstractMagicDynamicRegistry<UploadChannelInfo> {

	public UploadChannelMagicDynamicRegistry(UploadChannelMagicResourceStorage storage) {
		super(storage);
	}

	@EventListener(condition = "#event.type == 'upload-channel'")
	public void onFileEvent(FileEvent event) {
		processEvent(event);
	}

	@EventListener(condition = "#event.type == 'upload-channel'")
	public void onGroupEvent(GroupEvent event) {
		processEvent(event);
	}

	public UploadChannelInfo find(String name) {
		return name == null ? null : getMapping(name.trim());
	}
}
