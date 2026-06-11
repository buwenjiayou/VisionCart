package com.visioncart.service.search;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PowerBankProductClassifierTest {

    @Test
    void classifiesFinishedPortablePowerBanks() {
        assertThat(PowerBankProductClassifier.classify("充大容量20000毫安手机通用充电宝"))
                .isEqualTo(PowerBankProductClassifier.Type.FINISHED_POWER_BANK);
        assertThat(PowerBankProductClassifier.classify("20Ah透明外壳露电路板黄色LED指示灯充电宝"))
                .isEqualTo(PowerBankProductClassifier.Type.FINISHED_POWER_BANK);
        assertThat(PowerBankProductClassifier.classify("20000mAh移动电源带LED电量显示"))
                .isEqualTo(PowerBankProductClassifier.Type.FINISHED_POWER_BANK);
        assertThat(PowerBankProductClassifier.classify("透明外壳充电宝20Ah快充"))
                .isEqualTo(PowerBankProductClassifier.Type.FINISHED_POWER_BANK);
        assertThat(PowerBankProductClassifier.classify("自带线快充移动电源20000mAh"))
                .isEqualTo(PowerBankProductClassifier.Type.FINISHED_POWER_BANK);
    }

    @Test
    void rejectsNonTargetPowerBankLookalikes() {
        assertThat(PowerBankProductClassifier.classify("3.7v20ah聚合物电池20000毫安LED灯5锂电池30000mA"))
                .isEqualTo(PowerBankProductClassifier.Type.NON_TARGET);
        assertThat(PowerBankProductClassifier.classify("电台锂电池磷酸铁锂足量20AH汽车启动防爆电芯逆变器充电"))
                .isEqualTo(PowerBankProductClassifier.Type.NON_TARGET);
        assertThat(PowerBankProductClassifier.classify("适用手机碳纤维背膜8英寸改色贴纸摸防指纹A4尺寸外壳"))
                .isEqualTo(PowerBankProductClassifier.Type.NON_TARGET);
        assertThat(PowerBankProductClassifier.classify("22W移动电源模块主板充电宝快充电路板"))
                .isEqualTo(PowerBankProductClassifier.Type.NON_TARGET);
        assertThat(PowerBankProductClassifier.classify("18650移动电源盒DIY免焊充电宝套件"))
                .isEqualTo(PowerBankProductClassifier.Type.NON_TARGET);
        assertThat(PowerBankProductClassifier.classify("USB小夜灯充电宝随身灯节能插电护眼台灯学生led灯"))
                .isEqualTo(PowerBankProductClassifier.Type.NON_TARGET);
    }

    @Test
    void keepsOutdoorPowerBelowFinishedPortablePowerBanks() {
        assertThat(PowerBankProductClassifier.classify("220V大功率户外电源露营储能电源"))
                .isEqualTo(PowerBankProductClassifier.Type.RELATED_BUT_NOT_TARGET);
    }
}
