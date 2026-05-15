package com.genshin.gm.repository;

import com.genshin.gm.model.UserDevice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 用户设备数据访问层
 */
@Repository
public interface UserDeviceRepository extends JpaRepository<UserDevice, Long> {

    Optional<UserDevice> findByUsernameAndDeviceId(String username, String deviceId);

    List<UserDevice> findByUsername(String username);

    List<UserDevice> findByDeviceId(String deviceId);

    long countByDeviceId(String deviceId);
}
