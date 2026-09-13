package cn.geosentry.device.service;

import cn.geosentry.device.repository.DeviceTrajectoryRepository;
import org.springframework.stereotype.Service;

/**
 * 设备轨迹 bootstrap 组件（v1.9.0 P1）。
 *
 * <p>把 {@link cn.geosentry.device.scheduler.DeviceTrajectoryGenerator} 的"应用就绪事件"
 * 暴露为一个普通 Service，使 {@code DeviceMapController} 能手动触发同样的逻辑（演示用）。
 *
 * <p>注意：维持单一事实来源：{@code DeviceTrajectoryGenerator} 在应用就绪时已经自动跑过
 * 这里再跑会因检测到已有数据而立即返回，零副作用。
 */
@Service
public class DeviceTrajectoryBootstrap {

  private final DeviceTrajectoryRepository trajectoryRepository;
  private final cn.geosentry.device.scheduler.DeviceTrajectoryGenerator generator;

  public DeviceTrajectoryBootstrap(DeviceTrajectoryRepository trajectoryRepository,
                                    cn.geosentry.device.scheduler.DeviceTrajectoryGenerator generator) {
    this.trajectoryRepository = trajectoryRepository;
    this.generator = generator;
  }

  public void ensureTrajectoriesExist() {
    generator.generateIfNeeded();
  }

  public int countTrajectories() {
    return (int) trajectoryRepository.count();
  }
}
